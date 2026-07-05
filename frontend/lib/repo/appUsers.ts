import { db } from '@/lib/firestore';
import { FieldValue } from 'firebase-admin/firestore';

// ログイン成功時にemailで app_users をupsertする（created_atは初回のみ設定）
export async function upsertAppUser(email: string, name: string | null | undefined): Promise<void> {
  const ref = db.collection('app_users').doc(email);
  const snap = await ref.get();
  if (snap.exists) {
    await ref.update({ name: name ?? null });
  } else {
    await ref.set({
      email,
      name: name ?? null,
      created_at: FieldValue.serverTimestamp(),
    });
  }
}
