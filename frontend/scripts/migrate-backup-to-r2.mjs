// backup-storage/ に残っているローカルバックアップ済み画像を R2 へ一括アップロードする
// 一度きりの移行用スクリプト。実行後もローカルファイルは削除しない（安全のため手動確認後に削除する）。
//
// 実行方法(リポジトリルートで):
//   docker build --target builder -t oshi-migrate ./frontend
//   docker run --rm --env-file .env -v "$(pwd)/backup-data:/app/backup-storage" \
//     oshi-migrate node scripts/migrate-backup-to-r2.mjs
import { readdir, readFile } from 'fs/promises';
import path from 'path';
import { S3Client, PutObjectCommand, HeadObjectCommand } from '@aws-sdk/client-s3';

const BACKUP_ROOT = path.join(process.cwd(), 'backup-storage');

const CONTENT_TYPES = {
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.png': 'image/png',
  '.gif': 'image/gif',
  '.webp': 'image/webp',
};

const r2 = new S3Client({
  region: 'auto',
  endpoint: process.env.CLOUDFLARE_ACCOUNT_ENDPOINT,
  credentials: {
    accessKeyId: process.env.CLOUDFLARE_ACCOUNT_ACCESS_KEY_ID,
    secretAccessKey: process.env.CLOUDFLARE_ACCOUNT_API_SECRET,
  },
});
const BUCKET = process.env.CLOUDFLARE_R2_BUCKET_NAME;

async function walk(dir) {
  const entries = await readdir(dir, { withFileTypes: true });
  const files = [];
  for (const entry of entries) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      files.push(...(await walk(full)));
    } else {
      files.push(full);
    }
  }
  return files;
}

async function alreadyUploaded(key) {
  try {
    await r2.send(new HeadObjectCommand({ Bucket: BUCKET, Key: key }));
    return true;
  } catch {
    return false;
  }
}

async function main() {
  if (!BUCKET) throw new Error('CLOUDFLARE_R2_BUCKET_NAME が未設定です');

  const files = await walk(BACKUP_ROOT);
  console.log(`[migrate] ${files.length} files found under ${BACKUP_ROOT}`);

  let uploaded = 0;
  let skipped = 0;
  let failed = 0;

  for (const filePath of files) {
    const key = path.relative(BACKUP_ROOT, filePath).split(path.sep).join('/');
    try {
      if (await alreadyUploaded(key)) {
        skipped++;
        continue;
      }
      const body = await readFile(filePath);
      const ext = path.extname(filePath).toLowerCase();
      await r2.send(
        new PutObjectCommand({
          Bucket: BUCKET,
          Key: key,
          Body: body,
          ContentType: CONTENT_TYPES[ext] ?? 'application/octet-stream',
        })
      );
      uploaded++;
      if (uploaded % 100 === 0) console.log(`[migrate] uploaded ${uploaded}...`);
    } catch (err) {
      failed++;
      console.warn(`[migrate] failed ${key}: ${err.message}`);
    }
  }

  console.log(`[migrate] done. uploaded=${uploaded} skipped=${skipped} failed=${failed}`);
  if (failed > 0) process.exitCode = 1;
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
