import { NextResponse } from 'next/server';
import { auth } from '@/auth';
import * as media from '@/lib/repo/media';
import * as targetAccounts from '@/lib/repo/targetAccounts';
import { xWriteConfigured, friendlyWriteError } from '@/lib/xWrite';

// いいね・リポスト共通の送信処理。
// 認可チェック → skipIf（送信不要判定）→ X APIへ送信 → Firestoreへ送信済みを記録、の順。
// skipIf はトグル操作の誤爆防止用（いいねは送信済みなら何もしない。リポストは常に投げてよいので未指定）
export async function handleXAction(
  mediaKey: string,
  opts: {
    skipIf?: (target: media.MediaRow) => boolean;
    send: (tweetId: string) => Promise<void>;
    mark: (uid: string, tweetId: string) => Promise<void>;
  }
): Promise<NextResponse> {
  const session = await auth();
  const uid = session!.user!.uid!;

  const target = await media.getMedia(uid, mediaKey);
  const allowedXUserIds = await targetAccounts.listXUserIds(uid);
  if (!target || !allowedXUserIds.includes(target.x_user_id)) {
    return NextResponse.json({ error: '対象が見つかりません' }, { status: 404 });
  }

  if (opts.skipIf?.(target)) {
    return NextResponse.json({ ok: true, already: true });
  }

  if (!xWriteConfigured()) {
    return NextResponse.json(
      {
        error:
          'X_API_KEY / X_API_SECRET / X_ACCESS_TOKEN / X_ACCESS_TOKEN_SECRET が未設定のため送信できません。開発者ポータルでOAuth 1.0aのユーザー認証情報を発行して .env に設定してください。',
      },
      { status: 501 }
    );
  }

  try {
    await opts.send(target.tweet_id);
  } catch (err) {
    return NextResponse.json({ error: friendlyWriteError(err) }, { status: 502 });
  }

  await opts.mark(uid, target.tweet_id);
  return NextResponse.json({ ok: true, already: false });
}
