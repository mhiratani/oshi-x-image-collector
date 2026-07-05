import { NextResponse } from 'next/server';
import { getUsageStats } from '@/lib/repo/apiUsage';

export const dynamic = 'force-dynamic';

// GET /api/usage — X APIの呼び出し履歴とコスト（従量課金）の集計
// 認証チェックはmiddleware.tsが全ルートに適用済み
export async function GET() {
  const stats = await getUsageStats();
  return NextResponse.json(stats);
}
