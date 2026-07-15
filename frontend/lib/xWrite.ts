// X API v2 への書き込み（いいね・リポスト）クライアント。
// 収集系（worker/xapi.js）の X_BEARER_TOKEN はアプリ認証（読み取り専用）のため書き込みには使えず、
// OAuth 1.0a ユーザーコンテキストで署名して「自分のアカウントとして」操作する。
// 認証情報は開発者ポータルの App → Keys and tokens で発行する（Appの権限を Read and write に
// 変更してから Access Token and Secret を(再)生成する必要がある点に注意）。
import crypto from 'node:crypto';

const API_BASE = 'https://api.twitter.com/2';

const API_KEY = process.env.X_API_KEY;
const API_SECRET = process.env.X_API_SECRET;
const ACCESS_TOKEN = process.env.X_ACCESS_TOKEN;
const ACCESS_TOKEN_SECRET = process.env.X_ACCESS_TOKEN_SECRET;

// 4つの認証情報が揃っているときだけ、いいね・リポスト機能が有効になる（未設定なら機能ごと無効）
export function xWriteConfigured(): boolean {
  return Boolean(API_KEY && API_SECRET && ACCESS_TOKEN && ACCESS_TOKEN_SECRET);
}

// RFC 3986 のパーセントエンコード（encodeURIComponentが素通しする ! * ' ( ) も対象）
function pct(value: string): string {
  return encodeURIComponent(value).replace(
    /[!*'()]/g,
    (c) => `%${c.charCodeAt(0).toString(16).toUpperCase()}`
  );
}

// OAuth 1.0a の Authorization ヘッダを組み立てる。
// 対象URLはクエリ文字列なし・ボディはJSONの前提（JSONボディはOAuth 1.0aの署名対象外）
function oauthHeader(method: string, url: string): string {
  const params: Record<string, string> = {
    oauth_consumer_key: API_KEY!,
    oauth_nonce: crypto.randomBytes(16).toString('hex'),
    oauth_signature_method: 'HMAC-SHA1',
    oauth_timestamp: String(Math.floor(Date.now() / 1000)),
    oauth_token: ACCESS_TOKEN!,
    oauth_version: '1.0',
  };
  const paramString = Object.keys(params)
    .sort()
    .map((k) => `${pct(k)}=${pct(params[k])}`)
    .join('&');
  const base = [method.toUpperCase(), pct(url), pct(paramString)].join('&');
  const signingKey = `${pct(API_SECRET!)}&${pct(ACCESS_TOKEN_SECRET!)}`;
  params.oauth_signature = crypto.createHmac('sha1', signingKey).update(base).digest('base64');
  return `OAuth ${Object.keys(params)
    .sort()
    .map((k) => `${pct(k)}="${pct(params[k])}"`)
    .join(', ')}`;
}

async function xPost(path: string, body: Record<string, unknown>): Promise<void> {
  const url = `${API_BASE}${path}`;
  const res = await fetch(url, {
    method: 'POST',
    headers: {
      Authorization: oauthHeader('POST', url),
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(body),
  });
  if (res.status === 429) {
    const reset = res.headers.get('x-rate-limit-reset');
    throw new Error(`rate limited (reset epoch: ${reset ?? 'unknown'})`);
  }
  if (!res.ok) {
    throw new Error(`X API ${res.status}: ${await res.text()}`);
  }
}

// いいね・リポストのURL（/users/:id/...）に使う自分のuser id。
// アクセストークンから一意に決まるためプロセス内でキャッシュし、users/me は初回の1回だけ呼ぶ
let selfUserIdCache: string | null = null;

async function getSelfUserId(): Promise<string> {
  if (selfUserIdCache) return selfUserIdCache;
  const url = `${API_BASE}/users/me`;
  const res = await fetch(url, { headers: { Authorization: oauthHeader('GET', url) } });
  if (!res.ok) {
    throw new Error(`X API ${res.status}: ${await res.text()}`);
  }
  const json = await res.json();
  if (!json.data?.id) throw new Error('users/me のレスポンスから user id を取得できませんでした');
  selfUserIdCache = json.data.id;
  return json.data.id;
}

export async function likeTweet(tweetId: string): Promise<void> {
  const userId = await getSelfUserId();
  await xPost(`/users/${userId}/likes`, { tweet_id: tweetId });
}

export async function repostTweet(tweetId: string): Promise<void> {
  const userId = await getSelfUserId();
  await xPost(`/users/${userId}/retweets`, { tweet_id: tweetId });
}

// X APIの書き込みエラーをユーザー向けメッセージへ変換する
export function friendlyWriteError(err: unknown): string {
  const msg = err instanceof Error ? err.message : String(err);
  if (msg.includes('rate limited'))
    return 'X APIのレート制限中です。しばらく待ってから再実行してください。';
  if (msg.includes('X API 401'))
    return 'X APIの認証に失敗しました。X_API_KEY / X_API_SECRET / X_ACCESS_TOKEN / X_ACCESS_TOKEN_SECRET を確認してください。';
  if (msg.includes('X API 403'))
    return 'X APIに操作を拒否されました。Appの権限が「Read and write」か、権限変更後にAccess Tokenを再生成したかを確認してください。';
  return `X APIエラー: ${msg.slice(0, 200)}`;
}
