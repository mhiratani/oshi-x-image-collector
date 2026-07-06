import { NextResponse } from 'next/server';
import { auth } from '@/auth';
import { workerState } from '@/worker/state.js';
import { runCollectOnce } from '@/worker/batch.js';
import * as targetAccounts from '@/lib/repo/targetAccounts';
import { countUnrevealed } from '@/lib/repo/media';

export const dynamic = 'force-dynamic';

// GET /api/collect — ログインユーザーの推しリストにおける新着チェック結果とバッチの実行状態
export async function GET() {
  const session = await auth();
  const uid = session!.user!.uid!;

  const accounts = await targetAccounts.listAll(uid);
  const resolvedXUserIds = accounts.map((a) => a.x_user_id).filter((id): id is string => id !== null);

  const totalPending = await countUnrevealed(uid, resolvedXUserIds);
  const needsInitial = accounts.filter((a) => a.x_user_id !== null && a.last_fetched_id === null).length;
  const unresolved = accounts.filter((a) => a.x_user_id === null).length;

  return NextResponse.json({
    running: workerState.running,
    totalPending, // cronが取得済みだが未公開(revealed=false)の件数
    needsInitial, // ID解決済みだが初回クロール未実行
    unresolved, // screen_name 登録直後でID未解決
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
