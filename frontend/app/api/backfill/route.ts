import { NextResponse } from 'next/server';
import { auth } from '@/auth';
import { workerState } from '@/worker/state.js';
import { runBackfillOnce } from '@/worker/batch.js';
import * as targetAccounts from '@/lib/repo/targetAccounts';

export const dynamic = 'force-dynamic';

// GET /api/backfill — ログインユーザーの推しリストにおけるバックフィルの状態
// running: バッチ/バックフィル実行中か
// allDone: 自分の推しリスト（?account= 指定時はそのアカウントのみ）が過去を掘り尽くしたか
export async function GET(req: Request) {
  const session = await auth();
  const uid = session!.user!.uid!;
  const account = new URL(req.url).searchParams.get('account');

  const accounts = await targetAccounts.listAll(uid);
  const resolved = accounts.filter(
    (a) => a.x_user_id !== null && (!account || a.x_user_id === account)
  );
  const remaining = resolved.filter((a) => !a.backfill_done).length;

  return NextResponse.json({
    running: workerState.running,
    allDone: remaining === 0,
    lastError: workerState.lastError,
    progress: workerState.backfillProgress,
  });
}

// POST /api/backfill — バックフィル実行を起動（応答は待たせない）
// body: { account?: string } — 指定時は画面で絞り込み中のアカウントだけをバックフィルする
export async function POST(req: Request) {
  if (workerState.running) {
    return NextResponse.json({ started: false, reason: 'busy' }, { status: 409 });
  }
  const { account } = await req.json().catch(() => ({ account: undefined }));
  runBackfillOnce(account);
  return NextResponse.json({ started: true }, { status: 202 });
}
