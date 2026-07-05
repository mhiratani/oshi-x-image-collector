// X API v2 クライアント（Bearer Token 認証）
import { pool } from '@/lib/db';

const API_BASE = 'https://api.twitter.com/2';

const BEARER = process.env.X_BEARER_TOKEN;

// X APIは従量課金（返却リソース件数に応じた課金）。
// 単価は docker-compose.yml の環境変数で定義（改定時はそちらを更新）。
const UNIT_COST_USD = {
  user_read: Number(process.env.UNIT_COST_USD_USER_READ),
  posts_read: Number(process.env.UNIT_COST_USD_POSTS_READ),
};

// API呼び出し1回分の消費件数とコストを記録する（失敗しても本処理は止めない）。
// 件数0（新着なし等）でも「呼び出しは発生した」という事実を残すため記録する
async function logUsage({ purpose, endpoint, screenName, resource, quantity }) {
  const unitCost = UNIT_COST_USD[resource];
  try {
    await pool.query(
      `INSERT INTO api_usage_log (purpose, endpoint, screen_name, resource, quantity, unit_cost_usd, cost_usd)
       VALUES ($1, $2, $3, $4, $5, $6, $7)`,
      [purpose, endpoint, screenName ?? null, resource, quantity, unitCost, quantity * unitCost]
    );
  } catch (err) {
    console.error(`[usage] failed to log ${purpose}/${endpoint}: ${err.message}`);
  }
}

async function xFetch(path, params = {}) {
  const url = new URL(`${API_BASE}${path}`);
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== null) url.searchParams.set(k, String(v));
  }
  const res = await fetch(url, {
    headers: { Authorization: `Bearer ${BEARER}` },
  });
  if (res.status === 429) {
    const reset = res.headers.get('x-rate-limit-reset');
    const err = new Error(`rate limited (reset epoch: ${reset ?? 'unknown'})`);
    err.rateLimited = true;
    throw err;
  }
  if (!res.ok) {
    throw new Error(`X API ${res.status}: ${await res.text()}`);
  }
  return res.json();
}

// screen_name → user id の解決
export async function resolveUserId(screenName) {
  const json = await xFetch(`/users/by/username/${encodeURIComponent(screenName)}`);
  await logUsage({
    purpose: 'resolve',
    endpoint: 'users/by/username/:screen_name',
    screenName,
    resource: 'user_read',
    quantity: json.data ? 1 : 0,
  });
  return json.data?.id ?? null;
}

// あるユーザーのツイートから photo メディアを抽出して返す。
// sinceId: 差分取得（それより新しいもののみ）
// untilId: バックフィル（それより古いもののみ）
// 戻り値: { media, newestId, oldestId, exhausted, error }
//   exhausted = これ以上古いツイートがない（またはAPI上限3,200件に到達）
//   error    = ページ途中で失敗した場合のエラー。取得済みページ分の media は
//              捨てずに返す（クレジット切れ等でも成果を無駄にしない）
export async function fetchPhotoMedia({ userId, sinceId, untilId, maxPages, screenName }) {
  const purpose = untilId ? 'backfill' : 'collect';
  const media = [];
  let newestId = null;
  let oldestId = null;
  let exhausted = false;
  let error = null;
  let paginationToken;

  for (let page = 0; page < maxPages; page++) {
    const params = {
      max_results: 100,
      exclude: 'retweets',
      expansions: 'attachments.media_keys',
      'media.fields': 'url,type',
      'tweet.fields': 'created_at,attachments',
    };
    if (sinceId) params.since_id = sinceId;
    if (untilId) params.until_id = untilId;
    if (paginationToken) params.pagination_token = paginationToken;

    let json;
    try {
      json = await xFetch(`/users/${userId}/tweets`, params);
    } catch (err) {
      error = err;
      break;
    }
    await logUsage({
      purpose,
      endpoint: 'users/:id/tweets',
      screenName,
      resource: 'posts_read',
      quantity: json.data?.length ?? 0,
    });

    if (json.meta?.newest_id && !newestId) newestId = json.meta.newest_id;
    if (json.meta?.oldest_id) oldestId = json.meta.oldest_id;

    if (!json.data?.length) {
      exhausted = true;
      break;
    }

    const mediaByKey = new Map(
      (json.includes?.media ?? []).map((m) => [m.media_key, m])
    );

    for (const tweet of json.data) {
      const keys = tweet.attachments?.media_keys ?? [];
      for (const key of keys) {
        const m = mediaByKey.get(key);
        if (m?.type === 'photo' && m.url) {
          media.push({
            media_key: m.media_key,
            tweet_id: tweet.id,
            url: m.url,
            posted_at: tweet.created_at,
          });
        }
      }
    }

    paginationToken = json.meta?.next_token;
    if (!paginationToken) {
      exhausted = true;
      break;
    }
  }

  return { media, newestId, oldestId, exhausted, error };
}
