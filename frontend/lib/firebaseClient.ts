'use client';

import { getApps, initializeApp } from 'firebase/app';
import { GoogleAuthProvider, getAuth, signInWithPopup } from 'firebase/auth';

// クライアント側Firebase SDK。Android版と同じFirebaseプロジェクトを指す
// (apiKey/appIdは非機密情報。android-app/design.mdの前提と同じ)。
function getFirebaseApp() {
  if (getApps().length === 0) {
    initializeApp({
      apiKey: process.env.NEXT_PUBLIC_FIREBASE_API_KEY,
      projectId: process.env.NEXT_PUBLIC_FIREBASE_PROJECT_ID,
      appId: process.env.NEXT_PUBLIC_FIREBASE_APP_ID,
    });
  }
  return getApps()[0];
}

// GoogleでFirebase Authにサインインし、IDトークンを返す
// (このIDトークンをNextAuthのCredentialsプロバイダに渡してサーバー側で検証する)。
export async function signInWithGooglePopup(): Promise<string> {
  const auth = getAuth(getFirebaseApp());
  const result = await signInWithPopup(auth, new GoogleAuthProvider());
  return result.user.getIdToken();
}
