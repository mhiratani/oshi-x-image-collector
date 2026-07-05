// サーバー起動時に一度だけ呼ばれるNext.jsのフック。
// 旧worker独立コンテナが担っていたcronスケジューリングをここで登録する。
export async function register() {
  if (process.env.NEXT_RUNTIME === 'nodejs') {
    const { startScheduler } = await import('./worker/batch.js');
    startScheduler();
  }
}
