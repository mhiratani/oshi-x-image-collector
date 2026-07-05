import { db } from '@/lib/firestore';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';

const col = db.collection('user_subscriptions');

// 複合主キー(user_email, screen_name)相当を決定的なドキュメントIDで表現する。
// IDから逆算はせず、user_email/screen_nameは別途フィールドとしても持つ
function docId(userEmail: string, screenName: string): string {
  return `${userEmail}::${screenName}`;
}

export type Subscription = { screen_name: string; created_at: Date };

// Postgres の INSERT ... ON CONFLICT (user_email, screen_name) DO NOTHING 相当
export async function addSubscription(userEmail: string, screenName: string): Promise<void> {
  const ref = col.doc(docId(userEmail, screenName));
  await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (snap.exists) return;
    tx.set(ref, {
      user_email: userEmail,
      screen_name: screenName,
      created_at: FieldValue.serverTimestamp(),
    });
  });
}

export async function removeSubscription(userEmail: string, screenName: string): Promise<void> {
  await col.doc(docId(userEmail, screenName)).delete();
}

export async function isSubscribed(userEmail: string, screenName: string): Promise<boolean> {
  const snap = await col.doc(docId(userEmail, screenName)).get();
  return snap.exists;
}

export async function countSubscribers(screenName: string): Promise<number> {
  const agg = await col.where('screen_name', '==', screenName).count().get();
  return agg.data().count;
}

// ログインユーザーの推しリスト（購読した順）
export async function listForUser(userEmail: string): Promise<Subscription[]> {
  const snap = await col.where('user_email', '==', userEmail).orderBy('created_at', 'asc').get();
  return snap.docs.map((d) => {
    const data = d.data();
    return { screen_name: data.screen_name, created_at: (data.created_at as Timestamp).toDate() };
  });
}
