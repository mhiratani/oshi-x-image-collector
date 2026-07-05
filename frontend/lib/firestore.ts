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

export const db = globalForFirebase.firestoreDb ?? initFirestore();

globalForFirebase.firestoreDb = db;
