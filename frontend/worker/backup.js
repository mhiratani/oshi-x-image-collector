// X CDN からオリジナル画像を取得して Cloudflare R2 にバックアップする
// 配信は app/backups/[...path]/route.ts の専用ハンドラが R2 から都度取得して行う。
import sharp from 'sharp';
import { putObject } from '@/lib/r2';

const CONTENT_TYPES = {
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  png: 'image/png',
  gif: 'image/gif',
  webp: 'image/webp',
};

const MAX_ATTEMPTS = 5;
const THUMB_MAX_SIZE = 400;

// r2_backup_url が未設定のレコードをバッチでバックアップ
export async function backupPending(pool, batchSize) {
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
      await pool.query(
        `UPDATE media_assets SET r2_backup_url = $1 WHERE media_key = $2`,
        [relativeUrl, row.media_key]
      );
      ok++;
    } catch (err) {
      console.warn(`[backup] failed ${row.media_key}: ${err.message}`);
      await pool.query(
        `UPDATE media_assets SET backup_attempts = backup_attempts + 1 WHERE media_key = $1`,
        [row.media_key]
      );
    }
  }
  if (rows.length > 0) {
    console.log(`[backup] ${ok}/${rows.length} images backed up`);
  }
  return ok;
}

async function backupOne({ media_key, x_user_id, x_cdn_url }) {
  // ?name=orig でオリジナル解像度を取得
  const origUrl = `${x_cdn_url}?name=orig`;
  const res = await fetch(origUrl, {
    headers: { 'User-Agent': 'Mozilla/5.0 (oshi-image-app personal backup)' },
  });
  if (!res.ok) throw new Error(`CDN fetch ${res.status}`);

  const body = Buffer.from(await res.arrayBuffer());
  const ext = new URL(x_cdn_url).pathname.split('.').pop() || 'jpg';
  const key = `${x_user_id}/${media_key}.${ext}`;

  await putObject(key, body, CONTENT_TYPES[ext.toLowerCase()] ?? 'application/octet-stream');

  // グリッド表示用の軽量サムネイル（常にjpgに揃える。失敗しても本体の
  // バックアップ自体は成立させたいので、ここだけ個別にcatchする）
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
