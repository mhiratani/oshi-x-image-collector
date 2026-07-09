import { NextRequest, NextResponse } from 'next/server';
import { auth } from '@/auth';
import * as media from '@/lib/repo/media';
import * as targetAccounts from '@/lib/repo/targetAccounts';

export const dynamic = 'force-dynamic';

// PATCH /api/media/:mediaKey  body: { isFace?: boolean, isFavorite?: boolean }
// 顔フィルターの手動上書き・お気に入りの切り替え。自分の推しリストに入っている
// アカウントの画像であることを確認してから更新する
export async function PATCH(
  req: NextRequest,
  { params }: { params: { mediaKey: string } }
) {
  const session = await auth();
  const uid = session!.user!.uid!;

  const body = await req.json().catch(() => null);
  const hasIsFace = typeof body?.isFace === 'boolean';
  const hasIsFavorite = typeof body?.isFavorite === 'boolean';
  if (!hasIsFace && !hasIsFavorite) {
    return NextResponse.json({ error: 'isFaceまたはisFavorite(boolean)が必要です' }, { status: 400 });
  }

  const target = await media.getMedia(uid, params.mediaKey);
  const allowedXUserIds = await targetAccounts.listXUserIds(uid);
  if (!target || !allowedXUserIds.includes(target.x_user_id)) {
    return NextResponse.json({ error: '対象が見つかりません' }, { status: 404 });
  }

  if (hasIsFace) await media.updateFace(uid, params.mediaKey, body.isFace);
  if (hasIsFavorite) await media.updateFavorite(uid, params.mediaKey, body.isFavorite);
  return NextResponse.json({ ok: true });
}
