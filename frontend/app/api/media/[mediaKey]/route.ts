import { NextRequest, NextResponse } from 'next/server';
import { auth } from '@/auth';
import * as media from '@/lib/repo/media';
import { getSubscribedXUserIds } from '@/lib/repo/userAccounts';

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

  const target = await media.getMedia(params.mediaKey);
  const allowedXUserIds = await getSubscribedXUserIds(userEmail);
  if (!target || !allowedXUserIds.includes(target.x_user_id)) {
    return NextResponse.json({ error: '対象が見つかりません' }, { status: 404 });
  }

  await media.updateFace(params.mediaKey, body.isFace);
  return NextResponse.json({ ok: true });
}
