import { NextRequest, NextResponse } from 'next/server';
import * as shareLinks from '@/lib/repo/shareLinks';
import * as targetAccounts from '@/lib/repo/targetAccounts';
import { listMedia, type MediaCursor } from '@/lib/repo/media';
import { MEDIA_PAGE_SIZE, nextCursorFor } from '@/lib/mediaQuery';

export const dynamic = 'force-dynamic';

function parseCursor(cursor: string | null): MediaCursor | null {
  if (!cursor) return null;
  const sep = cursor.lastIndexOf('|');
  return { postedAt: new Date(cursor.slice(0, sep)), mediaKey: cursor.slice(sep + 1) };
}

// GET /api/s/<token>/media?cursor=...
// 共有リンク経由の公開エンドポイント（認証不要）。
// tokenからscreen_nameを引いて、DBに保存済みの画像だけを返す。X APIは呼ばない。
export async function GET(
  req: NextRequest,
  { params }: { params: { token: string } }
) {
  const link = await shareLinks.getByToken(params.token);
  if (!link || link.revoked_at) {
    return NextResponse.json({ error: 'リンクが無効です' }, { status: 404 });
  }
  const account = await targetAccounts.get(link.owner_uid, link.screen_name);
  if (!account?.x_user_id) {
    return NextResponse.json({ error: 'リンクが無効です' }, { status: 404 });
  }

  const { searchParams } = req.nextUrl;
  const cursor = parseCursor(searchParams.get('cursor'));
  const faceOnly = searchParams.get('faceOnly') === 'true';

  const rows = await listMedia({
    uid: link.owner_uid,
    xUserIds: [account.x_user_id],
    faceOnly,
    // お気に入りは所有者だけの情報のため、共有リンク経由では絞り込みを提供しない
    favoriteOnly: false,
    cursor,
    limit: MEDIA_PAGE_SIZE,
  });
  const items = rows.map((m) => ({
    media_key: m.media_key,
    tweet_id: m.tweet_id,
    x_cdn_url: m.x_cdn_url,
    r2_backup_url: m.r2_backup_url,
    posted_at: m.posted_at,
  }));

  return NextResponse.json({
    screenName: link.screen_name,
    items,
    nextCursor: nextCursorFor(items),
    hasMore: items.length === MEDIA_PAGE_SIZE,
  });
}
