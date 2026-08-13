import { NextResponse } from 'next/server';
import { auth } from '@/auth';
import { revealAll } from '@/lib/repo/media';
import * as targetAccounts from '@/lib/repo/targetAccounts';

export const dynamic = 'force-dynamic';

// POST /api/reveal — 新着バナーの「確認」操作。ログインユーザーの推しリストの範囲で
// 未読(revealed=false)の画像をまとめて既読にする（画像自体は保存時点で表示済み。
// X APIは呼ばない）
export async function POST() {
  const session = await auth();
  const uid = session!.user!.uid!;

  const xUserIds = await targetAccounts.listXUserIds(uid);
  await revealAll(uid, xUserIds);
  return NextResponse.json({ ok: true });
}
