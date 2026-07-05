import { cert, getApps, initializeApp } from 'firebase-admin/app';
import { getFirestore } from 'firebase-admin/firestore';

// Next.js のホットリロードでアプリ/クライアントが増殖しないように global に保持
const globalForFirebase = globalThis as unknown as {
  firestoreDb?: FirebaseFirestore.Firestore;
};

function initFirestore(): FirebaseFirestore.Firestore {
  if (getApps().length === 0) {
    initializeApp({
      credential: cert({
        projectId: process.env.FIREBASE_PROJECT_ID,
        clientEmail: process.env.FIREBASE_CLIENT_EMAIL,
        // .env には改行を \n エスケープした1行文字列で保存するため復元する
        privateKey: process.env.FIREBASE_PRIVATE_KEY?.replace(/\\n/g, '\n'),
      }),
    });
  }
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
