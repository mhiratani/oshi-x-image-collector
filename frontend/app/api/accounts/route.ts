import { NextRequest, NextResponse } from 'next/server';
import { pool } from '@/lib/db';
import { auth } from '@/auth';

export const dynamic = 'force-dynamic';

// GET /api/accounts — ログインユーザーが推しリストに入れているアカウント一覧（画像数付き）
export async function GET() {
  const session = await auth();
  const userEmail = session!.user!.email!;

  const { rows } = await pool.query(
    `SELECT a.screen_name, a.x_user_id, a.last_fetched_id, s.created_at,
            count(m.media_key)::int AS media_count
       FROM user_subscriptions s
       JOIN target_accounts a ON a.screen_name = s.screen_name
       LEFT JOIN media_assets m ON m.x_user_id = a.x_user_id
      WHERE s.user_email = $1
      GROUP BY a.screen_name, a.x_user_id, a.last_fetched_id, s.created_at
      ORDER BY s.created_at`,
    [userEmail]
  );
  return NextResponse.json({ accounts: rows });
}

// POST /api/accounts  body: { screenName: "@fruits_zipper" }
// target_accounts はユーザー間で共有。呼び出したユーザーの推しリストに
// 紐づけるのは user_subscriptions。x_user_id はワーカーが次回バッチで自動解決する
export async function POST(req: NextRequest) {
  const session = await auth();
  const userEmail = session!.user!.email!;

  const body = await req.json().catch(() => null);
  const raw = typeof body?.screenName === 'string' ? body.screenName : '';
  const screenName = raw.trim().replace(/^@/, '');

  if (!/^[A-Za-z0-9_]{1,15}$/.test(screenName)) {
    return NextResponse.json(
      { error: 'screen_name の形式が不正です（英数字と _ のみ、15文字以内）' },
      { status: 400 }
    );
  }

  await pool.query(
    `INSERT INTO target_accounts (screen_name) VALUES ($1)
     ON CONFLICT (screen_name) DO NOTHING`,
    [screenName]
  );
  await pool.query(
    `INSERT INTO user_subscriptions (user_email, screen_name) VALUES ($1, $2)
     ON CONFLICT (user_email, screen_name) DO NOTHING`,
    [userEmail, screenName]
  );
  return NextResponse.json({ ok: true, screenName });
}

// DELETE /api/accounts?screenName=xxx
// 呼び出したユーザーの推しリストから外す。他に誰も推していないアカウントに
// なった場合のみ target_accounts 自体を削除（収集画像も CASCADE 削除）
export async function DELETE(req: NextRequest) {
  const session = await auth();
  const userEmail = session!.user!.email!;

  const screenName = req.nextUrl.searchParams.get('screenName');
  if (!screenName) {
    return NextResponse.json({ error: 'screenName は必須です' }, { status: 400 });
  }

  await pool.query(
    `DELETE FROM user_subscriptions WHERE user_email = $1 AND screen_name = $2`,
    [userEmail, screenName]
  );
  const { rows } = await pool.query(
    `SELECT count(*)::int AS remaining FROM user_subscriptions WHERE screen_name = $1`,
    [screenName]
  );
  if (rows[0].remaining === 0) {
    await pool.query(`DELETE FROM target_accounts WHERE screen_name = $1`, [screenName]);
  }
  return NextResponse.json({ ok: true });
}
