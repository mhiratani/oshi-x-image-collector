import { NextRequest, NextResponse } from 'next/server';
import { pool } from '@/lib/db';
import { MEDIA_PAGE_SIZE, appendCursorCondition, nextCursorFor } from '@/lib/mediaQuery';

export const dynamic = 'force-dynamic';

// GET /api/s/<token>/media?cursor=...
// 共有リンク経由の公開エンドポイント（認証不要）。
// tokenからscreen_nameを引いて、DBに保存済みの画像だけを返す。X APIは呼ばない。
export async function GET(
  req: NextRequest,
  { params }: { params: { token: string } }
) {
  const { rows: linkRows } = await pool.query(
    `SELECT l.screen_name, a.x_user_id
       FROM share_links l
       JOIN target_accounts a ON a.screen_name = l.screen_name
      WHERE l.token = $1 AND l.revoked_at IS NULL`,
    [params.token]
  );
  const link = linkRows[0] as { screen_name: string; x_user_id: string } | undefined;
  if (!link) {
    return NextResponse.json({ error: 'リンクが無効です' }, { status: 404 });
  }

  const { searchParams } = req.nextUrl;
  const cursor = searchParams.get('cursor');
  const faceOnly = searchParams.get('faceOnly') === 'true';

  // x_user_id が既に分かっているので、media_assets に対して直接絞り込むだけでよい
  // （target_accounts への再JOINは不要）
  const conditions: string[] = ['m.x_user_id = $1', 'm.revealed'];
  const queryParams: unknown[] = [link.x_user_id];

  appendCursorCondition(conditions, queryParams, cursor);
  if (faceOnly) {
    conditions.push(`m.is_face = true`);
  }

  const { rows } = await pool.query(
    `SELECT m.media_key, m.tweet_id, m.x_cdn_url, m.r2_backup_url, m.posted_at
       FROM media_assets m
      WHERE ${conditions.join(' AND ')}
      ORDER BY m.posted_at DESC, m.media_key DESC
      LIMIT ${MEDIA_PAGE_SIZE}`,
    queryParams
  );

  return NextResponse.json({
    screenName: link.screen_name,
    items: rows,
    nextCursor: nextCursorFor(rows),
    hasMore: rows.length === MEDIA_PAGE_SIZE,
  });
}
