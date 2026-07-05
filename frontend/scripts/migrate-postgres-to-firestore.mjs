// Postgres → Firestore への一度きりのデータ移行スクリプト。
// db/init/*.sql で作成された6テーブルを読み出し、Web版の新しいFirestoreコレクション
// （docs/web-firestore-migration-design.md 参照）へ書き込む。
// ドキュメントIDは旧テーブルの主キーをそのまま使うため、再実行しても上書きになるだけで
// 重複は発生しない（安全に再実行できる）。
//
// 実行方法（リポジトリルートで、既存の frontend イメージのbuilderステージを使い回す）:
//   docker build --target builder -t oshi-migrate ./frontend
//   docker run --rm --env-file .env oshi-migrate node scripts/migrate-postgres-to-firestore.mjs
//
// 実行前提: .env に旧 PG_* 一式と新しい FIREBASE_* 一式の両方が設定されていること
// （移行が終わったら PG_* は削除してよい）。
import { Pool } from 'pg';
import { cert, initializeApp } from 'firebase-admin/app';
import { getFirestore, Timestamp } from 'firebase-admin/firestore';

const PAGE_SIZE = 500;
const FIRESTORE_BATCH_LIMIT = 500;

const DATABASE_URL =
  process.env.DATABASE_URL ??
  (process.env.PG_HOST &&
    `postgres://${process.env.PG_USER}:${process.env.PG_PASSWORD}@${process.env.PG_HOST}:${process.env.PG_PORT}/${process.env.PG_DB ?? 'oshi'}`);

if (!DATABASE_URL) throw new Error('DATABASE_URL (または PG_HOST 等) が未設定です');
if (!process.env.FIREBASE_PROJECT_ID) throw new Error('FIREBASE_PROJECT_ID が未設定です');

const pool = new Pool({ connectionString: DATABASE_URL, max: 5 });

initializeApp({
  credential: cert({
    projectId: process.env.FIREBASE_PROJECT_ID,
    clientEmail: process.env.FIREBASE_CLIENT_EMAIL,
    privateKey: process.env.FIREBASE_PRIVATE_KEY?.replace(/\\n/g, '\n'),
  }),
});
const db = getFirestore();

function chunk(items, size) {
  const out = [];
  for (let i = 0; i < items.length; i += size) out.push(items.slice(i, i + size));
  return out;
}

function ts(value) {
  return value ? Timestamp.fromDate(new Date(value)) : null;
}

// pgvector列は現状常にNULLだが、念のため文字列表現("[0.1,0.2]")をパースしておく
function parseEmbedding(value) {
  if (value == null) return null;
  if (Array.isArray(value)) return value;
  try {
    return JSON.parse(value);
  } catch {
    console.warn('[migrate] embedding のパースに失敗、null として移行します');
    return null;
  }
}

// orderByカラム昇順のシンプルなLIMIT/OFFSETページング
// （このアプリの規模＝個人〜友人利用なら十分に現実的な速度で終わる）
async function* readTable(sql, orderBy) {
  let offset = 0;
  for (;;) {
    const { rows } = await pool.query(`${sql} ORDER BY ${orderBy} LIMIT $1 OFFSET $2`, [PAGE_SIZE, offset]);
    if (rows.length === 0) return;
    yield rows;
    offset += rows.length;
    if (rows.length < PAGE_SIZE) return;
  }
}

async function writeDocs(collectionName, rows, idFor, toDoc) {
  let count = 0;
  for (const group of chunk(rows, FIRESTORE_BATCH_LIMIT)) {
    const batch = db.batch();
    for (const row of group) {
      batch.set(db.collection(collectionName).doc(idFor(row)), toDoc(row));
    }
    await batch.commit();
    count += group.length;
  }
  return count;
}

async function migrateAppUsers() {
  let total = 0;
  for await (const rows of readTable('SELECT * FROM app_users', 'email')) {
    total += await writeDocs('app_users', rows, (r) => r.email, (r) => ({
      email: r.email,
      name: r.name ?? null,
      created_at: ts(r.created_at),
    }));
  }
  return total;
}

async function migrateTargetAccounts() {
  let total = 0;
  for await (const rows of readTable('SELECT * FROM target_accounts', 'screen_name')) {
    total += await writeDocs('target_accounts', rows, (r) => r.screen_name, (r) => ({
      screen_name: r.screen_name,
      x_user_id: r.x_user_id ?? null,
      last_fetched_id: r.last_fetched_id ?? null,
      backfill_cursor: r.backfill_cursor ?? null,
      backfill_done: r.backfill_done ?? false,
      checked_at: ts(r.checked_at),
      created_at: ts(r.created_at),
    }));
  }
  return total;
}

async function migrateUserSubscriptions() {
  let total = 0;
  for await (const rows of readTable(
    'SELECT * FROM user_subscriptions',
    'user_email, screen_name'
  )) {
    total += await writeDocs(
      'user_subscriptions',
      rows,
      (r) => `${r.user_email}::${r.screen_name}`,
      (r) => ({
        user_email: r.user_email,
        screen_name: r.screen_name,
        created_at: ts(r.created_at),
      })
    );
  }
  return total;
}

async function migrateShareLinks() {
  let total = 0;
  for await (const rows of readTable('SELECT * FROM share_links', 'token')) {
    total += await writeDocs('share_links', rows, (r) => r.token, (r) => ({
      token: r.token,
      screen_name: r.screen_name,
      created_by: r.created_by,
      revoked_at: ts(r.revoked_at),
      created_at: ts(r.created_at),
    }));
  }
  return total;
}

async function migrateApiUsageLog() {
  let total = 0;
  // idをそのままドキュメントIDに使う（再実行時の重複防止。以後の新規ログは自動IDのまま運用する）
  for await (const rows of readTable('SELECT * FROM api_usage_log', 'id')) {
    total += await writeDocs('api_usage_log', rows, (r) => String(r.id), (r) => ({
      called_at: ts(r.called_at),
      purpose: r.purpose,
      endpoint: r.endpoint,
      screen_name: r.screen_name ?? null,
      resource: r.resource,
      quantity: r.quantity,
      unit_cost_usd: Number(r.unit_cost_usd),
      cost_usd: Number(r.cost_usd),
    }));
  }
  return total;
}

async function migrateMediaAssets() {
  let total = 0;
  for await (const rows of readTable('SELECT * FROM media_assets', 'media_key')) {
    total += await writeDocs('media_assets', rows, (r) => r.media_key, (r) => ({
      media_key: r.media_key,
      tweet_id: r.tweet_id,
      x_user_id: r.x_user_id,
      x_cdn_url: r.x_cdn_url,
      r2_backup_url: r.r2_backup_url ?? null,
      backed_up: r.r2_backup_url != null,
      backup_attempts: r.backup_attempts ?? 0,
      posted_at: ts(r.posted_at),
      ml_tags: r.ml_tags ?? null,
      embedding: parseEmbedding(r.embedding),
      is_face: r.is_face ?? null,
      face_confidence: r.face_confidence ?? null,
      face_reviewed: r.face_reviewed ?? false,
      revealed: r.revealed ?? true,
      created_at: ts(r.created_at),
    }));
  }
  return total;
}

async function pgCount(table) {
  const { rows } = await pool.query(`SELECT count(*)::int AS n FROM ${table}`);
  return rows[0].n;
}

async function firestoreCount(collectionName) {
  const agg = await db.collection(collectionName).count().get();
  return agg.data().count;
}

async function verify(table, collectionName) {
  const [pg, fs] = await Promise.all([pgCount(table), firestoreCount(collectionName)]);
  const mark = pg === fs ? 'OK' : 'MISMATCH';
  console.log(`[verify] ${table} -> ${collectionName}: postgres=${pg} firestore=${fs} [${mark}]`);
  return pg === fs;
}

async function main() {
  console.log('[migrate] start');

  console.log('[migrate] app_users ...');
  console.log(`  -> ${await migrateAppUsers()} 件`);

  console.log('[migrate] target_accounts ...');
  console.log(`  -> ${await migrateTargetAccounts()} 件`);

  console.log('[migrate] user_subscriptions ...');
  console.log(`  -> ${await migrateUserSubscriptions()} 件`);

  console.log('[migrate] share_links ...');
  console.log(`  -> ${await migrateShareLinks()} 件`);

  console.log('[migrate] api_usage_log ...');
  console.log(`  -> ${await migrateApiUsageLog()} 件`);

  console.log('[migrate] media_assets ...');
  console.log(`  -> ${await migrateMediaAssets()} 件`);

  console.log('[migrate] verifying counts ...');
  const results = await Promise.all([
    verify('app_users', 'app_users'),
    verify('target_accounts', 'target_accounts'),
    verify('user_subscriptions', 'user_subscriptions'),
    verify('share_links', 'share_links'),
    verify('api_usage_log', 'api_usage_log'),
    verify('media_assets', 'media_assets'),
  ]);

  await pool.end();

  if (results.every(Boolean)) {
    console.log('[migrate] done — all counts match');
  } else {
    console.error('[migrate] done — some counts did NOT match, review the MISMATCH lines above');
    process.exit(1);
  }
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
