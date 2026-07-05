import { NextRequest, NextResponse } from 'next/server';
import { pool } from '@/lib/db';
import { auth } from '@/auth';

export const dynamic = 'force-dynamic';

const PAGE_SIZE = 60;

// GET /api/media?cursor=<postedAtISO>|<mediaKey>&account=<x_user_id>
// posted_at 降順のキーセットページネーション（同一ツイート内の複数画像は
// posted_at が同値になるため media_key を第2キーにする）。
// ログインユーザーが推しリストに入れているアカウントの画像のみ返す。
export async function GET(req: NextRequest) {
  const session = await auth();
  const userEmail = session!.user!.email!;

  const { searchParams } = req.nextUrl;
  const cursor = searchParams.get('cursor');
  const account = searchParams.get('account');
  const faceOnly = searchParams.get('faceOnly') === 'true';

  const conditions: string[] = ['s.user_email = $1', 'm.revealed'];
  const params: unknown[] = [userEmail];

  if (cursor) {
    const sep = cursor.lastIndexOf('|');
    const postedAt = cursor.slice(0, sep);
    const mediaKey = cursor.slice(sep + 1);
    params.push(postedAt, mediaKey);
    conditions.push(`(m.posted_at, m.media_key) < ($${params.length - 1}::timestamptz, $${params.length})`);
  }
  if (account) {
    params.push(account);
    conditions.push(`m.x_user_id = $${params.length}`);
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
      LIMIT ${PAGE_SIZE}`,
    params
  );

  // 部分ページでもカーソルを返す: バックフィルで古い画像が増えたあと
  // 同じカーソルから続きを読み込めるようにするため
  const last = rows[rows.length - 1];
  const nextCursor = last
    ? `${new Date(last.posted_at).toISOString()}|${last.media_key}`
    : null;

  return NextResponse.json({
    items: rows,
    nextCursor,
    hasMore: rows.length === PAGE_SIZE,
  });
}
