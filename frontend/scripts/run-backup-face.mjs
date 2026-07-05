// バックアップ/顔検出バッチだけを手動で一気に消化するための一度きりのスクリプト。
// 新着取得(X API)は一切行わない。ロジックは worker/backup.js, worker/faceDetect.js と同じ
// （'@/' エイリアスが素のNodeでは解決できないため、ここでは重複させて自己完結させている）。
//
// 実行方法（リポジトリルートで、既存の frontend イメージをそのまま使い回す）:
//   docker run --rm --env-file .env   -v "$(pwd)/frontend/scripts:/app/scripts:ro"   -w /app   oshi-backup-face-tmp node scripts/run-backup-face.mjs
//
import { cert, initializeApp } from 'firebase-admin/app';
import { getFirestore, FieldValue } from 'firebase-admin/firestore';
import { S3Client, PutObjectCommand, GetObjectCommand } from '@aws-sdk/client-s3';
import sharp from 'sharp';
import * as tf from '@tensorflow/tfjs';
import * as blazeface from '@tensorflow-models/blazeface';

const BACKUP_BATCH_SIZE = 100
const FACE_BATCH_SIZE = 100
const MAX_ATTEMPTS = 5;
const THUMB_MAX_SIZE = 400;
const INPUT_SIZE = 256;

const CONTENT_TYPES = {
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  png: 'image/png',
  gif: 'image/gif',
  webp: 'image/webp',
};

initializeApp({
  credential: cert({
    projectId: process.env.FIREBASE_PROJECT_ID,
    clientEmail: process.env.FIREBASE_CLIENT_EMAIL,
    privateKey: process.env.FIREBASE_PRIVATE_KEY?.replace(/\\n/g, '\n'),
  }),
});
const db = getFirestore();
const mediaAssets = db.collection('media_assets');

const r2 = new S3Client({
  region: 'auto',
  endpoint: process.env.CLOUDFLARE_ACCOUNT_ENDPOINT,
  credentials: {
    accessKeyId: process.env.CLOUDFLARE_ACCOUNT_ACCESS_KEY_ID,
    secretAccessKey: process.env.CLOUDFLARE_ACCOUNT_API_SECRET,
  },
});
const BUCKET = process.env.CLOUDFLARE_R2_BUCKET_NAME;

async function putObject(key, body, contentType) {
  await r2.send(new PutObjectCommand({ Bucket: BUCKET, Key: key, Body: body, ContentType: contentType }));
}

async function getObjectBuffer(key) {
  const res = await r2.send(new GetObjectCommand({ Bucket: BUCKET, Key: key }));
  const bytes = await res.Body.transformToByteArray();
  return Buffer.from(bytes);
}

async function backupOne({ media_key, x_user_id, x_cdn_url }) {
  const origUrl = `${x_cdn_url}?name=orig`;
  const res = await fetch(origUrl, {
    headers: { 'User-Agent': 'Mozilla/5.0 (oshi-image-app personal backup)' },
  });
  if (!res.ok) throw new Error(`CDN fetch ${res.status}`);

  const body = Buffer.from(await res.arrayBuffer());
  const ext = new URL(x_cdn_url).pathname.split('.').pop() || 'jpg';
  const key = `${x_user_id}/${media_key}.${ext}`;

  await putObject(key, body, CONTENT_TYPES[ext.toLowerCase()] ?? 'application/octet-stream');

  try {
    const thumb = await sharp(body)
      .resize(THUMB_MAX_SIZE, THUMB_MAX_SIZE, { fit: 'inside', withoutEnlargement: true })
      .jpeg({ quality: 80 })
      .toBuffer();
    await putObject(`${x_user_id}/${media_key}_thumb.jpg`, thumb, 'image/jpeg');
  } catch (err) {
    console.warn(`[backup] thumbnail failed ${media_key}: ${err.message}`);
  }

  return `/backups/${key}`;
}

async function backupPending(batchSize) {
  // Firestoreは複数フィールドにまたがる不等号フィルタを扱えないため、backed_up==falseで
  // 多めに取得してから backup_attempts の上限判定だけJS側で行う（worker/media.jsと同じ方式）
  const snap = await mediaAssets
    .where('backed_up', '==', false)
    .orderBy('posted_at', 'desc')
    .limit(batchSize * 3)
    .get();
  const rows = snap.docs
    .map((d) => d.data())
    .filter((row) => (row.backup_attempts ?? 0) < MAX_ATTEMPTS)
    .slice(0, batchSize);

  let ok = 0;
  for (const row of rows) {
    try {
      const relativeUrl = await backupOne(row);
      await mediaAssets.doc(row.media_key).update({ r2_backup_url: relativeUrl, backed_up: true });
      ok++;
    } catch (err) {
      console.warn(`[backup] failed ${row.media_key}: ${err.message}`);
      await mediaAssets.doc(row.media_key).update({ backup_attempts: FieldValue.increment(1) });
    }
  }
  console.log(`[backup] ${ok}/${rows.length} images backed up`);
  return ok;
}

let modelPromise = null;
function getModel() {
  if (!modelPromise) modelPromise = blazeface.load();
  return modelPromise;
}

function r2KeyFor(r2BackupUrl) {
  return r2BackupUrl.replace(/^\/backups\//, '');
}

async function detectOne(model, r2BackupUrl) {
  const key = r2KeyFor(r2BackupUrl);
  const { data, info } = await sharp(await getObjectBuffer(key))
    .resize(INPUT_SIZE, INPUT_SIZE, { fit: 'fill' })
    .removeAlpha()
    .raw()
    .toBuffer({ resolveWithObject: true });

  const tensor = tf.tensor3d(data, [info.height, info.width, info.channels], 'int32');
  try {
    const predictions = await model.estimateFaces(tensor, false);
    const maxScore = predictions.reduce(
      (max, p) => Math.max(max, Array.isArray(p.probability) ? p.probability[0] : p.probability ?? 0),
      0
    );
    return { isFace: predictions.length > 0, confidence: maxScore };
  } finally {
    tensor.dispose();
  }
}

async function detectFaces(batchSize) {
  const snap = await mediaAssets
    .where('backed_up', '==', true)
    .where('is_face', '==', null)
    .where('face_reviewed', '==', false)
    .orderBy('posted_at', 'desc')
    .limit(batchSize)
    .get();
  const rows = snap.docs.map((d) => d.data());
  if (rows.length === 0) {
    console.log('[face] nothing to detect');
    return 0;
  }

  const model = await getModel();
  let ok = 0;
  for (const row of rows) {
    try {
      const { isFace, confidence } = await detectOne(model, row.r2_backup_url);
      await mediaAssets.doc(row.media_key).update({ is_face: isFace, face_confidence: confidence });
      ok++;
    } catch (err) {
      console.warn(`[face] failed ${row.media_key}: ${err.message}`);
    }
  }
  console.log(`[face] ${ok}/${rows.length} detected`);
  return ok;
}

async function main() {
  if (!process.env.FIREBASE_PROJECT_ID) throw new Error('FIREBASE_PROJECT_ID が未設定です');
  if (!BUCKET) throw new Error('CLOUDFLARE_R2_BUCKET_NAME が未設定です');

  await backupPending(BACKUP_BATCH_SIZE);
  await detectFaces(FACE_BATCH_SIZE);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
