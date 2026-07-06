import { getAuth } from 'firebase-admin/auth';
import { ensureFirebaseAdminApp } from '@/lib/firebaseAdmin';

// Node専用(firebase-admin/authに依存する)。Edge Runtimeのmiddleware.tsが読む
// auth.config.tsには絶対にimportしないこと(Edgeバンドラがfirebase-adminを
// 巻き込んでビルド/実行に失敗するため)。auth.ts(Node runtime)からのみ呼ぶ。
export async function verifyFirebaseIdToken(idToken: string) {
  ensureFirebaseAdminApp();
  return getAuth().verifyIdToken(idToken);
}
