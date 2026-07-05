import { NextRequest, NextResponse } from 'next/server';
import { auth } from '@/auth';
import { listMedia, type MediaCursor } from '@/lib/repo/media';
import { getSubscribedAccounts } from '@/lib/repo/userAccounts';
import { MEDIA_PAGE_SIZE, nextCursorFor } from '@/lib/mediaQuery';

export const dynamic = 'force-dynamic';

function parseCursor(cursor: string | null): MediaCursor | null {
  if (!cursor) return null;
  const sep = cursor.lastIndexOf('|');
  return { postedAt: new Date(cursor.slice(0, sep)), mediaKey: cursor.slice(sep + 1) };
}

// GET /api/media?cursor=<postedAtISO>|<mediaKey>&account=<x_user_id>
// posted_at 降順のキーセットページネーション（同一ツイート内の複数画像は
// posted_at が同値になるため media_key を第2キーにする）。
// ログインユーザーが推しリストに入れているアカウントの画像のみ返す。
export async function GET(req: NextRequest) {
  const session = await auth();
  const userEmail = session!.user!.email!;

  const { searchParams } = req.nextUrl;
  const cursor = parseCursor(searchParams.get('cursor'));
  const accountFilter = searchParams.get('account')?.split(',').filter(Boolean) ?? [];
  const faceOnly = searchParams.get('faceOnly') === 'true';

  const accounts = await getSubscribedAccounts(userEmail);
  const screenNameByXUserId = new Map(accounts.map((a) => [a.x_user_id, a.screen_name]));
  let xUserIds = accounts.map((a) => a.x_user_id).filter((id): id is string => id !== null);
  if (accountFilter.length > 0) {
    const allowed = new Set(accountFilter);
    xUserIds = xUserIds.filter((id) => allowed.has(id));
  }

  const rows = await listMedia({ xUserIds, faceOnly, cursor, limit: MEDIA_PAGE_SIZE });
  const items = rows.map((m) => ({
    media_key: m.media_key,
    tweet_id: m.tweet_id,
    x_user_id: m.x_user_id,
    x_cdn_url: m.x_cdn_url,
    r2_backup_url: m.r2_backup_url,
    posted_at: m.posted_at,
    ml_tags: m.ml_tags,
    is_face: m.is_face,
    screen_name: screenNameByXUserId.get(m.x_user_id) ?? null,
  }));

  return NextResponse.json({
    items,
    // 部分ページでもカーソルを返す: バックフィルで古い画像が増えたあと
    // 同じカーソルから続きを読み込めるようにするため
    nextCursor: nextCursorFor(items),
    hasMore: items.length === MEDIA_PAGE_SIZE,
  });
}
