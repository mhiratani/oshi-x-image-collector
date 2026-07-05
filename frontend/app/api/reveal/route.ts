import { NextResponse } from 'next/server';
import { pool } from '@/lib/db';
import { auth } from '@/auth';

export const dynamic = 'force-dynamic';

// POST /api/reveal — cronが裏で取得済みだが未公開(revealed=false)の画像を、
// ログインユーザーの推しリストの範囲でまとめて公開する（X APIは呼ばない）
export async function POST() {
  const session = await auth();
  const userEmail = session!.user!.email!;

  await pool.query(
    `UPDATE media_assets
        SET revealed = true
      WHERE NOT revealed
        AND x_user_id IN (
          SELECT a.x_user_id
            FROM target_accounts a
            JOIN user_subscriptions s ON s.screen_name = a.screen_name
           WHERE s.user_email = $1
        )`,
    [userEmail]
  );
  return NextResponse.json({ ok: true });
}
