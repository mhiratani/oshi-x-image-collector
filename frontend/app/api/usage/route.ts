import { NextResponse } from 'next/server';
import { auth } from '@/auth';
import { getUsageStats } from '@/lib/repo/apiUsage';

export const dynamic = 'force-dynamic';

// GET /api/usage — X APIの呼び出し履歴とコスト（従量課金）の集計
export async function GET() {
  const session = await auth();
  const uid = session!.user!.uid!;
  const stats = await getUsageStats(uid);
  return NextResponse.json(stats);
}
