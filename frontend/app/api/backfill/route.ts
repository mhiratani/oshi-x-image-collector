import { NextResponse } from 'next/server';
import { pool } from '@/lib/db';
import { auth } from '@/auth';
import { workerState } from '@/worker/state.js';
import { runBackfillOnce } from '@/worker/batch.js';

export const dynamic = 'force-dynamic';

// GET /api/backfill — ログインユーザーの推しリストにおけるバックフィルの状態
// running: バッチ/バックフィル実行中か
// allDone: 自分の推しリスト（?account= 指定時はそのアカウントのみ）が過去を掘り尽くしたか
export async function GET(req: Request) {
  const session = await auth();
  const userEmail = session!.user!.email!;
  const account = new URL(req.url).searchParams.get('account');

  const done = await pool.query(
    `SELECT count(*) FILTER (WHERE NOT a.backfill_done)::int AS remaining
       FROM user_subscriptions s
       JOIN target_accounts a ON a.screen_name = s.screen_name
      WHERE s.user_email = $1 AND a.x_user_id IS NOT NULL
        AND ($2::text IS NULL OR a.x_user_id = $2)`,
    [userEmail, account]
  );
  return NextResponse.json({
    running: workerState.running,
    allDone: done.rows[0].remaining === 0,
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
