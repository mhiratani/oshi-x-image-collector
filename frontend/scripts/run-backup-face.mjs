// バックアップ/顔検出バッチだけを手動で一気に消化するための一度きりのスクリプト。
// 新着取得(X API)は一切行わない。ロジックは worker/backup.js, worker/faceDetect.js と同じ
// （'@/' エイリアスが素のNodeでは解決できないため、ここでは重複させて自己完結させている）。
//
// 実行方法（リポジトリルートで、既存の frontend イメージをそのまま使い回す）:
//   docker run --rm --env-file .env   -v "$(pwd)/frontend/scripts:/app/scripts:ro"   -w /app   oshi-backup-face-tmp node scripts/run-backup-face.mjs
//
import { Pool } from 'pg';
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

// docker-compose.yml と同じ組み立て。DATABASE_URL が無ければ PG_* から構築する
const DATABASE_URL =
  process.env.DATABASE_URL ??
  (process.env.PG_HOST &&
    `postgres://${process.env.PG_USER}:${process.env.PG_PASSWORD}@${process.env.PG_HOST}:${process.env.PG_PORT}/${process.env.PG_DB ?? 'oshi'}`);

const pool = new Pool({ connectionString: DATABASE_URL, max: 5 });

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
  const { rows } = await pool.query(
    `SELECT media_key, x_user_id, x_cdn_url
       FROM media_assets
      WHERE r2_backup_url IS NULL AND backup_attempts < $1
      ORDER BY posted_at DESC
      LIMIT $2`,
    [MAX_ATTEMPTS, batchSize]
  );

  let ok = 0;
  for (const row of rows) {
    try {
      const relativeUrl = await backupOne(row);
      await pool.query(`UPDATE media_assets SET r2_backup_url = $1 WHERE media_key = $2`, [
        relativeUrl,
        row.media_key,
      ]);
      ok++;
    } catch (err) {
      console.warn(`[backup] failed ${row.media_key}: ${err.message}`);
      await pool.query(`UPDATE media_assets SET backup_attempts = backup_attempts + 1 WHERE media_key = $1`, [
        row.media_key,
      ]);
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
  const { rows } = await pool.query(
    `SELECT media_key, r2_backup_url
       FROM media_assets
      WHERE r2_backup_url IS NOT NULL AND is_face IS NULL AND NOT face_reviewed
      ORDER BY posted_at DESC
      LIMIT $1`,
    [batchSize]
  );
  if (rows.length === 0) {
    console.log('[face] nothing to detect');
    return 0;
  }

  const model = await getModel();
  let ok = 0;
  for (const row of rows) {
    try {
      const { isFace, confidence } = await detectOne(model, row.r2_backup_url);
      await pool.query(`UPDATE media_assets SET is_face = $1, face_confidence = $2 WHERE media_key = $3`, [
        isFace,
        confidence,
        row.media_key,
      ]);
      ok++;
    } catch (err) {
      console.warn(`[face] failed ${row.media_key}: ${err.message}`);
    }
  }
  console.log(`[face] ${ok}/${rows.length} detected`);
  return ok;
}

async function main() {
  if (!DATABASE_URL) throw new Error('DATABASE_URL (または PG_HOST 等) が未設定です');
  if (!BUCKET) throw new Error('CLOUDFLARE_R2_BUCKET_NAME が未設定です');

  await backupPending(BACKUP_BATCH_SIZE);
  await detectFaces(FACE_BATCH_SIZE);
  await pool.end();
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
