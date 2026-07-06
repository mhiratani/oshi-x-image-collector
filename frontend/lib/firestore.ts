import { getFirestore } from 'firebase-admin/firestore';
import { ensureFirebaseAdminApp } from '@/lib/firebaseAdmin';

// Next.js のホットリロードでアプリ/クライアントが増殖しないように global に保持
const globalForFirebase = globalThis as unknown as {
  firestoreDb?: FirebaseFirestore.Firestore;
};

function initFirestore(): FirebaseFirestore.Firestore {
  ensureFirebaseAdminApp();
  return getFirestore();
}

function getDb(): FirebaseFirestore.Firestore {
  if (!globalForFirebase.firestoreDb) {
    globalForFirebase.firestoreDb = initFirestore();
  }
  return globalForFirebase.firestoreDb;
}

// db初期化（cert検証を含む）をモジュール読み込み時ではなく実際の初回アクセス時まで遅延させる。
// `next build`のページデータ収集はモジュールを評価するだけでも実行されるため、
// トップレベルで即時初期化すると環境変数未設定のビルド環境でcert()が例外を投げてビルドが失敗する。
export const db = new Proxy({} as FirebaseFirestore.Firestore, {
  get(_target, prop, _receiver) {
    const target = getDb();
    const value = Reflect.get(target, prop, target);
    return typeof value === 'function' ? value.bind(target) : value;
  },
});
