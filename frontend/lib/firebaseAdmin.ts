import { cert, getApps, initializeApp } from 'firebase-admin/app';

// Firestore/Admin Authで共用するAdmin SDK初期化ガード。
// `next build`のページデータ収集はモジュールを評価するだけでも実行されるため、
// トップレベルで即時初期化すると環境変数未設定のビルド環境でcert()が例外を投げてビルドが失敗する。
// そのため呼び出し側(firestore.ts, verifyFirebaseIdToken.ts)が実際に使う直前にこれを呼ぶ。
export function ensureFirebaseAdminApp(): void {
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
}
