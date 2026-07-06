// トップレベルの target_accounts / media_assets / api_usage_log を
// users/{OWNER_UID}/targetAccounts, mediaAssets, apiUsageLog へ移すための一度きりの
// Firestore→Firestore移行スクリプト（docs/web-android-user-tree-unification-design.md 参照）。
// 既存の削除済み migrate-postgres-to-firestore.mjs (git show a100c86:frontend/scripts/migrate-postgres-to-firestore.mjs)
// と同じ「500件ずつバッチset、ドキュメントIDそのまま維持で冪等、件数検証」パターンを踏襲する。
// app_users / user_subscriptions は退役のため移行しない。
//
// 実行方法（リポジトリルートで、既存の frontend イメージのbuilderステージを使い回す）:
//   docker build --target builder -t oshi-migrate ./frontend
//   docker run --rm --env-file .env -e OWNER_UID=<uid> oshi-migrate node scripts/migrate-to-user-tree.mjs
//
// 実行前提: .env に FIREBASE_* 一式が設定済み、かつ OWNER_UID(移行先uid)が渡されていること。
// 旧トップレベルコレクションは削除しない（1〜2週間の観察期間後に手動削除する）。
import { cert, initializeApp } from 'firebase-admin/app';
import { FieldPath, getFirestore } from 'firebase-admin/firestore';

const PAGE_SIZE = 500;
const FIRESTORE_BATCH_LIMIT = 500;

const OWNER_UID = process.env.OWNER_UID;
if (!OWNER_UID) throw new Error('OWNER_UID が未設定です');
if (!process.env.FIREBASE_PROJECT_ID) throw new Error('FIREBASE_PROJECT_ID が未設定です');

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

// ドキュメントID昇順のシンプルなページング（このアプリの規模なら十分現実的な速度で終わる）
async function* readCollection(collectionName) {
  let lastDoc = null;
  for (;;) {
    let query = db.collection(collectionName).orderBy(FieldPath.documentId()).limit(PAGE_SIZE);
    if (lastDoc) query = query.startAfter(lastDoc.id);
    const snap = await query.get();
    if (snap.empty) return;
    yield snap.docs;
    lastDoc = snap.docs[snap.docs.length - 1];
    if (snap.docs.length < PAGE_SIZE) return;
  }
}

// ドキュメントIDをそのまま維持してコピーする（再実行しても上書きになるだけで安全）
async function copyCollection(sourceCollectionName, destCollectionRef) {
  let total = 0;
  for await (const docs of readCollection(sourceCollectionName)) {
    for (const group of chunk(docs, FIRESTORE_BATCH_LIMIT)) {
      const batch = db.batch();
      for (const doc of group) {
        batch.set(destCollectionRef.doc(doc.id), doc.data());
      }
      await batch.commit();
      total += group.length;
    }
  }
  return total;
}

async function migrateTargetAccounts() {
  const dest = db.collection('users').doc(OWNER_UID).collection('targetAccounts');
  return copyCollection('target_accounts', dest);
}

async function migrateMediaAssets() {
  const dest = db.collection('users').doc(OWNER_UID).collection('mediaAssets');
  return copyCollection('media_assets', dest);
}

async function migrateApiUsageLog() {
  const dest = db.collection('users').doc(OWNER_UID).collection('apiUsageLog');
  return copyCollection('api_usage_log', dest);
}

// share_linksは移動せず、owner_uidフィールドだけを一括追加する
async function stampOwnerUidOnShareLinks() {
  let total = 0;
  for await (const docs of readCollection('share_links')) {
    for (const group of chunk(docs, FIRESTORE_BATCH_LIMIT)) {
      const batch = db.batch();
      for (const doc of group) {
        batch.update(doc.ref, { owner_uid: OWNER_UID });
      }
      await batch.commit();
      total += group.length;
    }
  }
  return total;
}

async function sourceCount(collectionName) {
  const agg = await db.collection(collectionName).count().get();
  return agg.data().count;
}

async function destCount(subcollectionName) {
  const agg = await db
    .collection('users')
    .doc(OWNER_UID)
    .collection(subcollectionName)
    .count()
    .get();
  return agg.data().count;
}

async function verify(sourceCollectionName, destSubcollectionName) {
  const [src, dest] = await Promise.all([
    sourceCount(sourceCollectionName),
    destCount(destSubcollectionName),
  ]);
  const mark = src === dest ? 'OK' : 'MISMATCH';
  console.log(
    `[verify] ${sourceCollectionName} -> users/${OWNER_UID}/${destSubcollectionName}: source=${src} dest=${dest} [${mark}]`
  );
  return src === dest;
}

async function main() {
  console.log(`[migrate] start (OWNER_UID=${OWNER_UID})`);

  console.log('[migrate] target_accounts -> targetAccounts ...');
  console.log(`  -> ${await migrateTargetAccounts()} 件`);

  console.log('[migrate] media_assets -> mediaAssets ...');
  console.log(`  -> ${await migrateMediaAssets()} 件`);

  console.log('[migrate] api_usage_log -> apiUsageLog ...');
  console.log(`  -> ${await migrateApiUsageLog()} 件`);

  console.log('[migrate] share_links に owner_uid を追加 ...');
  console.log(`  -> ${await stampOwnerUidOnShareLinks()} 件`);

  console.log('[migrate] verifying counts ...');
  const results = await Promise.all([
    verify('target_accounts', 'targetAccounts'),
    verify('media_assets', 'mediaAssets'),
    verify('api_usage_log', 'apiUsageLog'),
  ]);

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
