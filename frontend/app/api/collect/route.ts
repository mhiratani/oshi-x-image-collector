import { NextResponse } from 'next/server';
import { pool } from '@/lib/db';
import { auth } from '@/auth';
import { workerState } from '@/worker/state.js';
import { runCollectOnce } from '@/worker/batch.js';

export const dynamic = 'force-dynamic';

// GET /api/collect — ログインユーザーの推しリストにおける新着チェック結果とバッチの実行状態
export async function GET() {
  const session = await auth();
  const userEmail = session!.user!.email!;

  const pending = await pool.query(
    `SELECT
       (SELECT count(*)::int
          FROM media_assets m
          JOIN target_accounts a ON a.x_user_id = m.x_user_id
          JOIN user_subscriptions s ON s.screen_name = a.screen_name
         WHERE s.user_email = $1 AND NOT m.revealed) AS total,
       (SELECT count(*)::int
          FROM user_subscriptions s
          JOIN target_accounts a ON a.screen_name = s.screen_name
         WHERE s.user_email = $1 AND a.x_user_id IS NOT NULL AND a.last_fetched_id IS NULL) AS needs_initial,
       (SELECT count(*)::int
          FROM user_subscriptions s
          JOIN target_accounts a ON a.screen_name = s.screen_name
         WHERE s.user_email = $1 AND a.x_user_id IS NULL) AS unresolved`,
    [userEmail]
  );
  const row = pending.rows[0];
  return NextResponse.json({
    running: workerState.running,
    totalPending: row.total, // cronが取得済みだが未公開(revealed=false)の件数
    needsInitial: row.needs_initial, // ID解決済みだが初回クロール未実行
    unresolved: row.unresolved, // screen_name 登録直後でID未解決
    lastError: workerState.lastError,
  });
}

// POST /api/collect — 新着の本取得を起動（応答は待たせない）
export async function POST() {
  if (workerState.running) {
    return NextResponse.json({ started: false, reason: 'busy' }, { status: 409 });
  }
  runCollectOnce();
  return NextResponse.json({ started: true }, { status: 202 });
}
