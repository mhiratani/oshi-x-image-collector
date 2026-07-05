import { db } from '@/lib/firestore';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { chunk, deleteQueryInBatches, updateQueryInBatches, FIRESTORE_BATCH_LIMIT, IN_QUERY_LIMIT } from './shared';

const col = db.collection('media_assets');

// バックアップ失敗の打ち切り回数（worker/backup.js と同じ値をここでも参照する）
const MAX_BACKUP_ATTEMPTS = 5;

export type MediaRow = {
  media_key: string;
  tweet_id: string;
  x_user_id: string;
  x_cdn_url: string;
  r2_backup_url: string | null;
  posted_at: Date;
  ml_tags: Record<string, unknown> | null;
  is_face: boolean | null;
  face_confidence: number | null;
  backup_attempts: number;
  revealed: boolean;
};

function fromDoc(doc: FirebaseFirestore.QueryDocumentSnapshot): MediaRow {
  const data = doc.data();
  return {
    media_key: data.media_key,
    tweet_id: data.tweet_id,
    x_user_id: data.x_user_id,
    x_cdn_url: data.x_cdn_url,
    r2_backup_url: data.r2_backup_url ?? null,
    posted_at: (data.posted_at as Timestamp).toDate(),
    ml_tags: data.ml_tags ?? null,
    is_face: data.is_face ?? null,
    face_confidence: data.face_confidence ?? null,
    backup_attempts: data.backup_attempts ?? 0,
    revealed: data.revealed,
  };
}

// screen_name だけ登録された直後などに新規取得したメディアをまとめて保存する。
// 既存の media_key はスキップする（Postgresの ON CONFLICT (media_key) DO NOTHING 相当）
export async function insertMediaBatch(
  media: { media_key: string; tweet_id: string; url: string; posted_at: string }[],
  xUserId: string,
  revealed: boolean
): Promise<void> {
  for (const group of chunk(media, FIRESTORE_BATCH_LIMIT)) {
    const refs = group.map((m) => col.doc(m.media_key));
    const existing = await db.getAll(...refs);
    const batch = db.batch();
    existing.forEach((snap, i) => {
      if (snap.exists) return;
      const m = group[i];
      batch.set(snap.ref, {
        media_key: m.media_key,
        tweet_id: m.tweet_id,
        x_user_id: xUserId,
        x_cdn_url: m.url,
        r2_backup_url: null,
        backed_up: false,
        backup_attempts: 0,
        posted_at: Timestamp.fromDate(new Date(m.posted_at)),
        ml_tags: null,
        embedding: null,
        is_face: null,
        face_confidence: null,
        face_reviewed: false,
        revealed,
        created_at: FieldValue.serverTimestamp(),
      });
    });
    await batch.commit();
  }
}

export type MediaCursor = { postedAt: Date; mediaKey: string };

// /api/media, /api/s/[token]/media 共通のキーセットページネーション付き一覧取得。
// xUserIds が複数件なら 'in'（最大30件）、1件なら '==' で絞り込む
export async function listMedia(params: {
  xUserIds: string[];
  faceOnly: boolean;
  cursor: MediaCursor | null;
  limit: number;
}): Promise<MediaRow[]> {
  const { xUserIds, faceOnly, cursor, limit } = params;
  if (xUserIds.length === 0) return [];

  let query: FirebaseFirestore.Query =
    xUserIds.length === 1
      ? col.where('x_user_id', '==', xUserIds[0])
      : col.where('x_user_id', 'in', xUserIds.slice(0, IN_QUERY_LIMIT));

  query = query.where('revealed', '==', true);
  if (faceOnly) query = query.where('is_face', '==', true);
  query = query.orderBy('posted_at', 'desc').orderBy('media_key', 'desc');
  if (cursor) {
    query = query.startAfter(Timestamp.fromDate(cursor.postedAt), cursor.mediaKey);
  }
  query = query.limit(limit);

  const snap = await query.get();
  return snap.docs.map(fromDoc);
}

export async function getMedia(mediaKey: string): Promise<MediaRow | null> {
  const snap = await col.doc(mediaKey).get();
  return snap.exists ? fromDoc(snap as FirebaseFirestore.QueryDocumentSnapshot) : null;
}

export async function updateFace(mediaKey: string, isFace: boolean): Promise<void> {
  await col.doc(mediaKey).update({ is_face: isFace, face_reviewed: true });
}

export async function countForXUserId(xUserId: string): Promise<number> {
  const agg = await col.where('x_user_id', '==', xUserId).count().get();
  return agg.data().count;
}

export async function countUnrevealed(xUserIds: string[]): Promise<number> {
  if (xUserIds.length === 0) return 0;
  const agg = await col
    .where('x_user_id', 'in', xUserIds.slice(0, IN_QUERY_LIMIT))
    .where('revealed', '==', false)
    .count()
    .get();
  return agg.data().count;
}

// cronが裏で取得済みだが未公開の画像を、渡された x_user_id の範囲でまとめて公開する
export async function revealAll(xUserIds: string[]): Promise<void> {
  for (const idsGroup of chunk(xUserIds, IN_QUERY_LIMIT)) {
    await updateQueryInBatches(
      col.where('x_user_id', 'in', idsGroup).where('revealed', '==', false),
      { revealed: true }
    );
  }
}

// XのTweet ID(Snowflake)は生成時刻とほぼ単調増加するため、posted_at昇順の先頭を
// 「最も古いツイート」の代わりに使う（MIN(tweet_id::bigint)の代替、バックフィル起点解決用）
export async function getOldestTweetId(xUserId: string): Promise<string | null> {
  const snap = await col.where('x_user_id', '==', xUserId).orderBy('posted_at', 'asc').limit(1).get();
  if (snap.empty) return null;
  return snap.docs[0].data().tweet_id;
}

// r2_backup_url が未設定のレコードをバッチ分だけ返す（backup_attempts上限は事後フィルタ）。
// Firestoreは不等号フィルタを2フィールドにまたがって使えないため、backed_up==falseで
// orderByした上位を多めに取得し、backup_attemptsの上限判定だけJS側で行う
export async function listPendingBackup(batchSize: number): Promise<MediaRow[]> {
  const snap = await col
    .where('backed_up', '==', false)
    .orderBy('posted_at', 'desc')
    .limit(batchSize * 3)
    .get();
  return snap.docs
    .map(fromDoc)
    .filter((row) => row.backup_attempts < MAX_BACKUP_ATTEMPTS)
    .slice(0, batchSize);
}

export async function markBackedUp(mediaKey: string, r2BackupUrl: string): Promise<void> {
  await col.doc(mediaKey).update({ r2_backup_url: r2BackupUrl, backed_up: true });
}

export async function markBackupFailed(mediaKey: string): Promise<void> {
  await col.doc(mediaKey).update({ backup_attempts: FieldValue.increment(1) });
}

// バックアップ済み・未判定(is_face IS NULL)・手動レビュー未実施の画像を判定対象として返す
export async function listPendingFaceDetection(batchSize: number): Promise<MediaRow[]> {
  const snap = await col
    .where('backed_up', '==', true)
    .where('is_face', '==', null)
    .where('face_reviewed', '==', false)
    .orderBy('posted_at', 'desc')
    .limit(batchSize)
    .get();
  return snap.docs.map(fromDoc);
}

export async function markFaceResult(mediaKey: string, isFace: boolean, confidence: number): Promise<void> {
  await col.doc(mediaKey).update({ is_face: isFace, face_confidence: confidence });
}

// target_accounts 削除時のカスケード削除で使う
export async function deleteAllMediaForXUserId(xUserId: string): Promise<void> {
  await deleteQueryInBatches(col.where('x_user_id', '==', xUserId));
}
