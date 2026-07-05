// バッチの実行状態（旧workerコンテナの /status 相当）。
// Next.js のホットリロードでも増殖しないよう global に保持（lib/db.ts と同じパターン）
const globalForWorker = globalThis;

export const workerState =
  globalForWorker.oshiWorkerState ??
  { running: false, lastError: null };

globalForWorker.oshiWorkerState = workerState;
