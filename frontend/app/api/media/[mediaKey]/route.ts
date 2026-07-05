import { NextRequest, NextResponse } from 'next/server';
import { pool } from '@/lib/db';
import { auth } from '@/auth';

export const dynamic = 'force-dynamic';

// PATCH /api/media/:mediaKey  body: { isFace: boolean }
// 顔フィルターの手動上書き。自分の推しリストに入っているアカウントの
// 画像であることを user_subscriptions 経由で確認してから更新する
export async function PATCH(
  req: NextRequest,
  { params }: { params: { mediaKey: string } }
) {
  const session = await auth();
  const userEmail = session!.user!.email!;

  const body = await req.json().catch(() => null);
  if (typeof body?.isFace !== 'boolean') {
    return NextResponse.json({ error: 'isFace(boolean)が必要です' }, { status: 400 });
  }

  const { rows } = await pool.query(
    `UPDATE media_assets m
        SET is_face = $1, face_reviewed = true
       FROM target_accounts a
       JOIN user_subscriptions s ON s.screen_name = a.screen_name
      WHERE m.x_user_id = a.x_user_id
        AND m.media_key = $2
        AND s.user_email = $3
      RETURNING m.media_key`,
    [body.isFace, params.mediaKey, userEmail]
  );

  if (rows.length === 0) {
    return NextResponse.json({ error: '対象が見つかりません' }, { status: 404 });
  }
  return NextResponse.json({ ok: true });
}
