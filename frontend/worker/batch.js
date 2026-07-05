// バッチ処理本体（旧worker独立コンテナから移設）。
// cron による定期実行と、フロントのボタンからの単発実行の両方をここでまとめる。
// DB接続はNext.js側の共有プール(lib/db.ts)をそのまま使う。
import cron from 'node-cron';
import { pool } from '@/lib/db';
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
  console.log(`[batch] start ${new Date().toISOString()}`);
  try {
    // cronで新着を実際に取得・保存まで行う（画面には revealed=false のため出さない）。
    // 表示への反映は「最新を取得」ボタン（/api/reveal）でまとめて公開する
    await resolvePendingUserIds();
    await collectAllAccounts();
    if (BACKFILL) await backfillAllAccounts();
    await backupPending(pool, BACKUP_BATCH_SIZE);
    await detectFaces(pool, FACE_BATCH_SIZE);
  } catch (err) {
    console.error('[batch] error:', err.message);
  } finally {
    workerState.running = false;
    console.log('[batch] done');
  }
}

// screen_name だけ登録されたアカウントの x_user_id を解決
async function resolvePendingUserIds() {
  const { rows } = await pool.query(
    `SELECT screen_name FROM target_accounts WHERE x_user_id IS NULL`
  );
  for (const { screen_name } of rows) {
    try {
      const id = await resolveUserId(screen_name);
      if (id) {
        await pool.query(
          `UPDATE target_accounts SET x_user_id = $1 WHERE screen_name = $2`,
          [id, screen_name]
        );
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

async function insertMedia(media, xUserId, revealed) {
  for (const m of media) {
    await pool.query(
      `INSERT INTO media_assets (media_key, tweet_id, x_user_id, x_cdn_url, posted_at, revealed)
       VALUES ($1, $2, $3, $4, $5, $6)
       ON CONFLICT (media_key) DO NOTHING`,
      [m.media_key, m.tweet_id, xUserId, m.url, m.posted_at, revealed]
    );
  }
}

// 新着方向の差分取得（since_id）。初回クロール（last_fetched_id が空）も兼ねる。
// 初回クロールは取得したその場で画面に出す(revealed=true)が、既にlast_fetched_idが
// あった＝定期実行での差分取得は revealed=false で保存し、ボタン押下時の公開を待つ
async function collectAllAccounts() {
  const { rows: accounts } = await pool.query(
    `SELECT screen_name, x_user_id, last_fetched_id
       FROM target_accounts WHERE x_user_id IS NOT NULL`
  );

  for (const account of accounts) {
    try {
      const isInitialCrawl = !account.last_fetched_id;
      const { media, newestId, oldestId, error } = await fetchPhotoMedia({
        userId: account.x_user_id,
        sinceId: account.last_fetched_id,
        maxPages: MAX_PAGES,
        screenName: account.screen_name,
      });

      // 途中でエラーになっても取得済みページ分は保存する
      await insertMedia(media, account.x_user_id, isInitialCrawl);

      // 途中エラー時は last_fetched_id を進めない
      // （進めると未取得の中間ページが二度と取れなくなるため）
      if (!error && newestId) {
        await pool.query(
          `UPDATE target_accounts SET last_fetched_id = $1, checked_at = now()
            WHERE x_user_id = $2`,
          [newestId, account.x_user_id]
        );
      } else if (!error) {
        // 新着なし: チェック済みフラグだけ更新
        await pool.query(
          `UPDATE target_accounts SET checked_at = now()
            WHERE x_user_id = $1`,
          [account.x_user_id]
        );
      }
      // 初回クロール時は「どこまで遡ったか」をバックフィルの起点として記録
      if (!account.last_fetched_id && oldestId) {
        await pool.query(
          `UPDATE target_accounts SET backfill_cursor = $1
            WHERE x_user_id = $2 AND backfill_cursor IS NULL`,
          [oldestId, account.x_user_id]
        );
      }
      console.log(`[collect] @${account.screen_name}: ${media.length} new photos`);

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
  const { rows: accounts } = await pool.query(
    `SELECT screen_name, x_user_id, backfill_cursor
       FROM target_accounts
      WHERE x_user_id IS NOT NULL AND NOT backfill_done
        AND ($1::text IS NULL OR x_user_id = $1)`,
    [xUserId ?? null]
  );

  for (const account of accounts) {
    try {
      // 旧バージョンで収集済みなどで cursor が無い場合は保存済み最古ツイートから
      let untilId = account.backfill_cursor;
      if (!untilId) {
        const { rows } = await pool.query(
          `SELECT min(tweet_id::bigint)::text AS oldest
             FROM media_assets WHERE x_user_id = $1`,
          [account.x_user_id]
        );
        untilId = rows[0].oldest;
      }

      const { media, oldestId, exhausted, error } = await fetchPhotoMedia({
        userId: account.x_user_id,
        untilId,
        maxPages: BACKFILL_PAGES,
        screenName: account.screen_name,
      });

      // 途中でエラーになっても取得済みページ分は保存し、カーソルも進める
      // （バックフィルは古い方向に進むだけなので部分成功でも整合する）。
      // ユーザーが明示的に押した操作なので即座に公開する
      await insertMedia(media, account.x_user_id, true);

      await pool.query(
        `UPDATE target_accounts
            SET backfill_cursor = COALESCE($1, backfill_cursor),
                backfill_done = $2
          WHERE x_user_id = $3`,
        [oldestId ?? untilId, exhausted && !error, account.x_user_id]
      );

      console.log(
        `[backfill] @${account.screen_name}: ${media.length} photos` +
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
  console.log(`[backfill] manual run triggered${xUserId ? ` (account: ${xUserId})` : ''}`);
  try {
    await backfillAllAccounts(xUserId);
    await backupPending(pool, BACKUP_BATCH_SIZE);
    await detectFaces(pool, FACE_BATCH_SIZE);
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
  console.log('[collect] manual run triggered');
  try {
    // アカウント登録直後にボタンを押しても動くよう、ID解決も行う
    await resolvePendingUserIds();
    await collectAllAccounts();
    await backupPending(pool, BACKUP_BATCH_SIZE);
    await detectFaces(pool, FACE_BATCH_SIZE);
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
