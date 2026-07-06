import { db } from '@/lib/firestore';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { deleteQueryInBatches } from './shared';

const col = () => db.collection('share_links');

export type ShareLink = {
  token: string;
  screen_name: string;
  owner_uid: string;
  revoked_at: Date | null;
};

function fromSnap(snap: FirebaseFirestore.DocumentSnapshot): ShareLink | null {
  if (!snap.exists) return null;
  const data = snap.data()!;
  return {
    token: data.token,
    screen_name: data.screen_name,
    owner_uid: data.owner_uid,
    revoked_at: data.revoked_at ? (data.revoked_at as Timestamp).toDate() : null,
  };
}

export async function create(token: string, screenName: string, ownerUid: string): Promise<void> {
  await col().doc(token).set({
    token,
    screen_name: screenName,
    owner_uid: ownerUid,
    revoked_at: null,
    created_at: FieldValue.serverTimestamp(),
  });
}

// 有効な（revoked_atがnullの）最新のリンクを1件返す。無ければ使い回さず新規発行する
export async function findActiveToken(screenName: string): Promise<string | null> {
  const snap = await col()
    .where('screen_name', '==', screenName)
    .where('revoked_at', '==', null)
    .orderBy('created_at', 'desc')
    .limit(1)
    .get();
  return snap.empty ? null : (snap.docs[0].data().token as string);
}

export async function getByToken(token: string): Promise<ShareLink | null> {
  return fromSnap(await col().doc(token).get());
}

// 無効化。既に無効化済みなら false を返す（呼び出し元で404扱いにする）
export async function revoke(token: string): Promise<boolean> {
  const link = await getByToken(token);
  if (!link || link.revoked_at) return false;
  await col().doc(token).update({ revoked_at: FieldValue.serverTimestamp() });
  return true;
}

// target_accounts 削除時のカスケード削除で使う
export async function deleteAllShareLinksForScreenName(screenName: string): Promise<void> {
  await deleteQueryInBatches(col().where('screen_name', '==', screenName));
}
