import { NextRequest, NextResponse } from 'next/server';
import { randomBytes } from 'crypto';
import { pool } from '@/lib/db';
import { auth } from '@/auth';

export const dynamic = 'force-dynamic';

// POST /api/share  body: { screenName }
// 推しリストに入れているアカウントに対して共有リンクを発行する。
// 既に有効なリンクがあればそれを使い回す（無闇に増やさない）
export async function POST(req: NextRequest) {
  const session = await auth();
  const userEmail = session!.user!.email!;

  const body = await req.json().catch(() => null);
  const screenName = typeof body?.screenName === 'string' ? body.screenName : '';

  const { rows: subRows } = await pool.query(
    `SELECT 1 FROM user_subscriptions WHERE user_email = $1 AND screen_name = $2`,
    [userEmail, screenName]
  );
  if (subRows.length === 0) {
    return NextResponse.json({ error: '推しリストに登録されていないアカウントです' }, { status: 404 });
  }

  const { rows: existing } = await pool.query(
    `SELECT token FROM share_links WHERE screen_name = $1 AND revoked_at IS NULL
      ORDER BY created_at DESC LIMIT 1`,
    [screenName]
  );
  if (existing[0]) {
    return NextResponse.json({ token: existing[0].token });
  }

  const token = randomBytes(24).toString('base64url');
  await pool.query(
    `INSERT INTO share_links (token, screen_name, created_by) VALUES ($1, $2, $3)`,
    [token, screenName, userEmail]
  );
  return NextResponse.json({ token });
}

// DELETE /api/share?token=xxx — 共有リンクを無効化する
export async function DELETE(req: NextRequest) {
  const session = await auth();
  const userEmail = session!.user!.email!;
  const token = req.nextUrl.searchParams.get('token');
  if (!token) {
    return NextResponse.json({ error: 'token は必須です' }, { status: 400 });
  }

  const { rowCount } = await pool.query(
    `UPDATE share_links SET revoked_at = now()
      WHERE token = $1 AND revoked_at IS NULL
        AND screen_name IN (SELECT screen_name FROM user_subscriptions WHERE user_email = $2)`,
    [token, userEmail]
  );
  if (rowCount === 0) {
    return NextResponse.json({ error: '対象のリンクが見つかりません（既に無効化済みの可能性があります）' }, { status: 404 });
  }
  return NextResponse.json({ ok: true });
}
