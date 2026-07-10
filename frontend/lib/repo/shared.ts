import { db } from '@/lib/firestore';

// Firestoreのバッチwrite/getAllは1回あたり500件が実質上限なので、それに合わせて分割する
export const FIRESTORE_BATCH_LIMIT = 500;

// `where(field, 'in', [...])` は最大30件までしか指定できない制約
// （推しリストが30アカウントを超える場合は先頭30件に絞られる既知の制限）
export const IN_QUERY_LIMIT = 30;

export function chunk<T>(items: T[], size: number): T[][] {
  const out: T[][] = [];
  for (let i = 0; i < items.length; i += size) out.push(items.slice(i, i + size));
  return out;
}

// クエリに一致する全ドキュメントに500件ずつバッチで同じ更新をかける
// （FirestoreにはUPDATE ... WHERE 相当が無いため、まずクエリしてから更新する）
export async function updateQueryInBatches(
  query: FirebaseFirestore.Query,
  update: Record<string, unknown>
): Promise<void> {
  for (;;) {
    const snap = await query.limit(FIRESTORE_BATCH_LIMIT).get();
    if (snap.empty) return;
    const batch = db.batch();
    snap.docs.forEach((d) => batch.update(d.ref, update));
    await batch.commit();
    if (snap.size < FIRESTORE_BATCH_LIMIT) return;
  }
}
