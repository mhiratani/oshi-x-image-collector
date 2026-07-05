// 顔フィルター: バックアップ済み画像に対して軽量ML(BlazeFace)で
// 「顔がメインの画像か」を判定し、media_assets.is_face に記録する。
// node-canvasはAlpine/ARM64でネイティブビルドが不安定なため使わず、
// 画像デコードは既に動作実績のあるsharpでraw pixelを取り出してtensor化する。
import * as tf from '@tensorflow/tfjs';
import * as blazeface from '@tensorflow-models/blazeface';
import sharp from 'sharp';
import { getObjectBuffer } from '@/lib/r2';
import { listPendingFaceDetection, markFaceResult } from '@/lib/repo/media';

const INPUT_SIZE = 256;

let modelPromise = null;
function getModel() {
  if (!modelPromise) modelPromise = blazeface.load();
  return modelPromise;
}

// r2_backup_url は配信用の相対パス "/backups/<key>"。R2のオブジェクトキーに変換する
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

// 未判定(is_face IS NULL)かつバックアップ済み、かつ手動レビュー未実施の画像を判定する
export async function detectFaces(batchSize) {
  const rows = await listPendingFaceDetection(batchSize);
  if (rows.length === 0) return 0;

  const model = await getModel();
  let ok = 0;
  for (const row of rows) {
    try {
      const { isFace, confidence } = await detectOne(model, row.r2_backup_url);
      await markFaceResult(row.media_key, isFace, confidence);
      ok++;
    } catch (err) {
      console.warn(`[face] failed ${row.media_key}: ${err.message}`);
    }
  }
  console.log(`[face] ${ok}/${rows.length} detected`);
  return ok;
}
