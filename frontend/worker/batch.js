// バッチ処理本体（旧worker独立コンテナから移設）。
// cron による定期実行と、フロントのボタンからの単発実行の両方をここでまとめる。
// DBアクセスは Firestore の repo 層(lib/repo/*)経由で行う。
import cron from 'node-cron';
import * as targetAccounts from '@/lib/repo/targetAccounts';
import * as media from '@/lib/repo/media';
import { resolveUserId, fetchPhotoMedia } from './xapi.js';
import { backupPending } from './backup.js';
import { detectFaces } from './faceDetect.js';
import { workerState } from './state.js';

const CRON_SCHEDULE = process.env.CRON_SCHEDULE ?? '*/15 * * * *';
const MAX_PAGES = Number(process.env.MAX_PAGES_PER_ACCOUNT ?? 3);
const BACKUP_BATCH_SIZE = Number(process.env.BACKUP_BATCH_SIZE ?? 50);
const FACE_BATCH_SIZE = Number(process.env.FACE_BATCH_SIZE ?? 20);
const BACKFILL = (process.env.BACKFILL ?? 'false') === 'true';
const BACKFILL_PAGES = Number(process.env.BACKFILL_PAGES ?? 5);

function recordError(phase, screenName, err) {
  workerState.lastError = `[${phase}] @${screenName}: ${err.message.slice(0, 300)}`;
  console.error(`[${phase}] @${screenName}: ${err.message}`);
}

export async function runBatch() {
  if (workerState.running) {
    console.log('[batch] previous run still in progress, skipping');
    return;
  }
  workerState.running = true;
  workerState.lastError = null;
  workerState.backfillProgress = { done: 0, total: 0 };
  console.log(`[batch] start ${new Date().toISOString()}`);
  try {
    // cronで新着を実際に取得・保存まで行う（画面には revealed=false のため出さない）。
    // 表示への反映は「最新を取得」ボタン（/api/reveal）でまとめて公開する
    await resolvePendingUserIds();
    await collectAllAccounts();
    if (BACKFILL) await backfillAllAccounts();
    await backupPending(BACKUP_BATCH_SIZE);
    await detectFaces(FACE_BATCH_SIZE);
  } catch (err) {
    console.error('[batch] error:', err.message);
  } finally {
    workerState.running = false;
    console.log('[batch] done');
  }
}

// screen_name だけ登録されたアカウントの x_user_id を解決
async function resolvePendingUserIds() {
  const accounts = await targetAccounts.listUnresolved();
  for (const { screen_name } of accounts) {
    try {
      const id = await resolveUserId(screen_name);
      if (id) {
        await targetAccounts.setXUserId(screen_name, id);
        console.log(`[resolve] @${screen_name} -> ${id}`);
      } else {
        console.warn(`[resolve] @${screen_name} not found on X`);
      }
    } catch (err) {
      recordError('resolve', screen_name, err);
      if (err.rateLimited) return; // レート制限時は次回に持ち越し
    }
  }
}

// 新着方向の差分取得（since_id）。初回クロール（last_fetched_id が空）も兼ねる。
// 初回クロールは取得したその場で画面に出す(revealed=true)が、既にlast_fetched_idが
// あった＝定期実行での差分取得は revealed=false で保存し、ボタン押下時の公開を待つ
async function collectAllAccounts() {
  const accounts = await targetAccounts.listResolved();

  for (const account of accounts) {
    try {
      const isInitialCrawl = !account.last_fetched_id;
      const { media: fetched, newestId, oldestId, error } = await fetchPhotoMedia({
        userId: account.x_user_id,
        sinceId: account.last_fetched_id,
        maxPages: MAX_PAGES,
        screenName: account.screen_name,
      });

      // 途中でエラーになっても取得済みページ分は保存する
      await media.insertMediaBatch(fetched, account.x_user_id, isInitialCrawl);

      // 途中エラー時は last_fetched_id を進めない
      // （進めると未取得の中間ページが二度と取れなくなるため）
      if (!error && newestId) {
        await targetAccounts.updateAfterCollect(account.screen_name, {
          last_fetched_id: newestId,
          checked_at: true,
        });
      } else if (!error) {
        // 新着なし: チェック済みフラグだけ更新
        await targetAccounts.updateAfterCollect(account.screen_name, { checked_at: true });
      }
      // 初回クロール時は「どこまで遡ったか」をバックフィルの起点として記録
      if (!account.last_fetched_id && oldestId) {
        await targetAccounts.setBackfillCursorIfEmpty(account.screen_name, oldestId);
      }
      console.log(`[collect] @${account.screen_name}: ${fetched.length} new photos`);

      if (error) {
        recordError('collect', account.screen_name, error);
        if (error.rateLimited) {
          console.warn('[collect] rate limited — aborting this run');
          return;
        }
      }
    } catch (err) {
      recordError('collect', account.screen_name, err);
    }
  }
}

// 過去方向のバックフィル（until_id）。取得済み範囲より古いものだけを読む。
// 掘り尽くす（またはAPI上限3,200件に到達する）と backfill_done=true になり
// 以降このアカウントではAPIを呼ばない。
// xUserId を指定すると、そのアカウントだけを対象にする（画面でユーザー絞り込み中の手動実行用）
async function backfillAllAccounts(xUserId) {
  const accounts = await targetAccounts.listForBackfill(xUserId);

  // 画面に「◯/◯件取得中」を出すための目安。実際にはアカウントごとに
  // 遡り切って途中で終わることもあるため、あくまで上限値
  workerState.backfillProgress = { done: 0, total: accounts.length * BACKFILL_PAGES * 100 };

  for (const account of accounts) {
    try {
      // 旧バージョンで収集済みなどで cursor が無い場合は保存済み最古ツイートから
      let untilId = account.backfill_cursor;
      if (!untilId) {
        untilId = await media.getOldestTweetId(account.x_user_id);
      }

      const { media: fetched, oldestId, exhausted, error } = await fetchPhotoMedia({
        userId: account.x_user_id,
        untilId,
        maxPages: BACKFILL_PAGES,
        screenName: account.screen_name,
        onPage: (n) => {
          workerState.backfillProgress.done += n;
        },
      });

      // 途中でエラーになっても取得済みページ分は保存し、カーソルも進める
      // （バックフィルは古い方向に進むだけなので部分成功でも整合する）。
      // ユーザーが明示的に押した操作なので即座に公開する
      await media.insertMediaBatch(fetched, account.x_user_id, true);

      await targetAccounts.updateBackfill(account.screen_name, {
        backfill_cursor: oldestId ?? untilId,
        backfill_done: exhausted && !error,
      });

      console.log(
        `[backfill] @${account.screen_name}: ${fetched.length} photos` +
          (exhausted && !error
            ? ' — 完了（これ以上過去はありません）'
            : ` (cursor: ${oldestId ?? untilId})`)
      );

      if (error) {
        recordError('backfill', account.screen_name, error);
        if (error.rateLimited) {
          console.warn('[backfill] rate limited — aborting this run');
          return;
        }
      }
    } catch (err) {
      recordError('backfill', account.screen_name, err);
    }
  }
}

// フロントの「過去を読み込む」ボタンから起動する単発バックフィル（+ 直後にバックアップ）
// xUserId を指定すると、そのアカウントだけを対象にする（画面でユーザー絞り込み中の場合）
export async function runBackfillOnce(xUserId) {
  workerState.running = true;
  workerState.lastError = null;
  workerState.backfillProgress = { done: 0, total: 0 };
  console.log(`[backfill] manual run triggered${xUserId ? ` (account: ${xUserId})` : ''}`);
  try {
    await backfillAllAccounts(xUserId);
    await backupPending(BACKUP_BATCH_SIZE);
    await detectFaces(FACE_BATCH_SIZE);
  } catch (err) {
    console.error('[backfill] error:', err.message);
  } finally {
    workerState.running = false;
    console.log('[backfill] manual run done');
  }
}

// フロントの「最新を取得」ボタンから起動する単発収集（+ 直後にバックアップ）
export async function runCollectOnce() {
  workerState.running = true;
  workerState.lastError = null;
  workerState.backfillProgress = { done: 0, total: 0 };
  console.log('[collect] manual run triggered');
  try {
    // アカウント登録直後にボタンを押しても動くよう、ID解決も行う
    await resolvePendingUserIds();
    await collectAllAccounts();
    await backupPending(BACKUP_BATCH_SIZE);
    await detectFaces(FACE_BATCH_SIZE);
  } catch (err) {
    console.error('[collect] error:', err.message);
  } finally {
    workerState.running = false;
    console.log('[collect] manual run done');
  }
}

// Next.jsサーバー起動時に一度だけ呼ばれる（instrumentation.ts から）
let scheduled = false;
export function startScheduler() {
  if (scheduled) return; // ホットリロード等での二重登録防止
  scheduled = true;
  console.log(
    `[worker] scheduled: "${CRON_SCHEDULE}" (backfill: ${BACKFILL ? `on, ${BACKFILL_PAGES} pages/run` : 'off'})`
  );
  cron.schedule(CRON_SCHEDULE, runBatch);
  if ((process.env.RUN_ON_START ?? 'true') === 'true') {
    runBatch();
  }
}
