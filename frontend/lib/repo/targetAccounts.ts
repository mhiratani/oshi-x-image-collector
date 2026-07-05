import { db } from '@/lib/firestore';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { deleteAllMediaForXUserId } from './media';
import { deleteAllShareLinksForScreenName } from './shareLinks';

const col = db.collection('target_accounts');

export type TargetAccount = {
  screen_name: string;
  x_user_id: string | null;
  last_fetched_id: string | null;
  backfill_cursor: string | null;
  backfill_done: boolean;
  checked_at: Date | null;
  created_at: Date | null;
};

function fromSnap(snap: FirebaseFirestore.DocumentSnapshot): TargetAccount | null {
  if (!snap.exists) return null;
  const data = snap.data()!;
  return {
    screen_name: data.screen_name,
    x_user_id: data.x_user_id ?? null,
    last_fetched_id: data.last_fetched_id ?? null,
    backfill_cursor: data.backfill_cursor ?? null,
    backfill_done: data.backfill_done ?? false,
    checked_at: data.checked_at ? (data.checked_at as Timestamp).toDate() : null,
    created_at: data.created_at ? (data.created_at as Timestamp).toDate() : null,
  };
}

// target_accounts はユーザー間で共有。無ければデフォルト値で作成、既にあれば何もしない
// (Postgres の INSERT ... ON CONFLICT (screen_name) DO NOTHING 相当)
export async function createIfNotExists(screenName: string): Promise<void> {
  const ref = col.doc(screenName);
  await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (snap.exists) return;
    tx.set(ref, {
      screen_name: screenName,
      x_user_id: null,
      last_fetched_id: null,
      backfill_cursor: null,
      backfill_done: false,
      checked_at: null,
      created_at: FieldValue.serverTimestamp(),
    });
  });
}

export async function get(screenName: string): Promise<TargetAccount | null> {
  return fromSnap(await col.doc(screenName).get());
}

export async function getMany(screenNames: string[]): Promise<Map<string, TargetAccount>> {
  if (screenNames.length === 0) return new Map();
  const refs = screenNames.map((s) => col.doc(s));
  const snaps = await db.getAll(...refs);
  const out = new Map<string, TargetAccount>();
  snaps.forEach((snap) => {
    const account = fromSnap(snap);
    if (account) out.set(account.screen_name, account);
  });
  return out;
}

export async function listUnresolved(): Promise<TargetAccount[]> {
  const snap = await col.where('x_user_id', '==', null).get();
  return snap.docs.map((d) => fromSnap(d)!).filter(Boolean);
}

export async function setXUserId(screenName: string, xUserId: string): Promise<void> {
  await col.doc(screenName).update({ x_user_id: xUserId });
}

export async function listResolved(): Promise<TargetAccount[]> {
  const snap = await col.where('x_user_id', '!=', null).get();
  return snap.docs.map((d) => fromSnap(d)!).filter(Boolean);
}

export async function updateAfterCollect(
  screenName: string,
  update: { last_fetched_id?: string; checked_at?: boolean; backfill_cursor?: string }
): Promise<void> {
  const payload: Record<string, unknown> = {};
  if (update.last_fetched_id !== undefined) payload.last_fetched_id = update.last_fetched_id;
  if (update.checked_at) payload.checked_at = FieldValue.serverTimestamp();
  if (update.backfill_cursor !== undefined) payload.backfill_cursor = update.backfill_cursor;
  if (Object.keys(payload).length === 0) return;
  await col.doc(screenName).update(payload);
}

// 初回クロール時のみ backfill_cursor を設定する（既に値がある場合は上書きしない）
export async function setBackfillCursorIfEmpty(screenName: string, oldestId: string): Promise<void> {
  const ref = col.doc(screenName);
  await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (!snap.exists || snap.data()!.backfill_cursor) return;
    tx.update(ref, { backfill_cursor: oldestId });
  });
}

// x_user_id を指定すると、そのアカウントだけに絞る（画面でユーザー絞り込み中の手動実行用）
export async function listForBackfill(xUserId?: string): Promise<TargetAccount[]> {
  let query = col.where('x_user_id', '!=', null).where('backfill_done', '==', false);
  if (xUserId) {
    query = col.where('x_user_id', '==', xUserId).where('backfill_done', '==', false);
  }
  const snap = await query.get();
  return snap.docs.map((d) => fromSnap(d)!).filter(Boolean);
}

export async function updateBackfill(
  screenName: string,
  update: { backfill_cursor: string | null; backfill_done: boolean }
): Promise<void> {
  const payload: Record<string, unknown> = { backfill_done: update.backfill_done };
  if (update.backfill_cursor) payload.backfill_cursor = update.backfill_cursor;
  await col.doc(screenName).update(payload);
}

// target_accounts 削除。media_assets(x_user_id)・share_links(screen_name) を
// 先にカスケード削除してから本体を消す（PostgresのON DELETE CASCADE相当を手動実装）
export async function deleteCascade(screenName: string): Promise<void> {
  const account = await get(screenName);
  if (!account) return;
  if (account.x_user_id) {
    await deleteAllMediaForXUserId(account.x_user_id);
  }
  await deleteAllShareLinksForScreenName(screenName);
  await col.doc(screenName).delete();
}
