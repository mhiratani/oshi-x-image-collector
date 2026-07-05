import { NextRequest, NextResponse } from 'next/server';
import { pool } from '@/lib/db';
import { auth } from '@/auth';
import { MEDIA_PAGE_SIZE, appendCursorCondition, nextCursorFor } from '@/lib/mediaQuery';

export const dynamic = 'force-dynamic';

// GET /api/media?cursor=<postedAtISO>|<mediaKey>&account=<x_user_id>
// posted_at 降順のキーセットページネーション（同一ツイート内の複数画像は
// posted_at が同値になるため media_key を第2キーにする）。
// ログインユーザーが推しリストに入れているアカウントの画像のみ返す。
export async function GET(req: NextRequest) {
  const session = await auth();
  const userEmail = session!.user!.email!;

  const { searchParams } = req.nextUrl;
  const cursor = searchParams.get('cursor');
  const accounts = searchParams.get('account')?.split(',').filter(Boolean) ?? [];
  const faceOnly = searchParams.get('faceOnly') === 'true';

  const conditions: string[] = ['s.user_email = $1', 'm.revealed'];
  const params: unknown[] = [userEmail];

  appendCursorCondition(conditions, params, cursor);
  if (accounts.length > 0) {
    params.push(accounts);
    conditions.push(`m.x_user_id = ANY($${params.length}::text[])`);
  }
  if (faceOnly) {
    conditions.push(`m.is_face = true`);
  }

  const { rows } = await pool.query(
    `SELECT m.media_key, m.tweet_id, m.x_user_id, m.x_cdn_url,
            m.r2_backup_url, m.posted_at, m.ml_tags, m.is_face, a.screen_name
       FROM media_assets m
       JOIN target_accounts a ON a.x_user_id = m.x_user_id
       JOIN user_subscriptions s ON s.screen_name = a.screen_name
      WHERE ${conditions.join(' AND ')}
      ORDER BY m.posted_at DESC, m.media_key DESC
      LIMIT ${MEDIA_PAGE_SIZE}`,
    params
  );

  return NextResponse.json({
    items: rows,
    // 部分ページでもカーソルを返す: バックフィルで古い画像が増えたあと
    // 同じカーソルから続きを読み込めるようにするため
    nextCursor: nextCursorFor(rows),
    hasMore: rows.length === MEDIA_PAGE_SIZE,
  });
}
