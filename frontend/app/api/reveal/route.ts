import { NextResponse } from 'next/server';
import { auth } from '@/auth';
import { revealAll } from '@/lib/repo/media';
import { getSubscribedXUserIds } from '@/lib/repo/userAccounts';

export const dynamic = 'force-dynamic';

// POST /api/reveal — cronが裏で取得済みだが未公開(revealed=false)の画像を、
// ログインユーザーの推しリストの範囲でまとめて公開する（X APIは呼ばない）
export async function POST() {
  const session = await auth();
  const userEmail = session!.user!.email!;

  const xUserIds = await getSubscribedXUserIds(userEmail);
  await revealAll(xUserIds);
  return NextResponse.json({ ok: true });
}
