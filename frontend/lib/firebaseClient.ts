'use client';

import { getApps, initializeApp } from 'firebase/app';
import { GoogleAuthProvider, getAuth, signInWithPopup } from 'firebase/auth';

// クライアント側Firebase SDK。Android版と同じFirebaseプロジェクトを指す
// (apiKey/appIdは非機密情報。android-app/design.mdの前提と同じ)。
function getFirebaseApp() {
  if (getApps().length === 0) {
    initializeApp({
      apiKey: process.env.NEXT_PUBLIC_FIREBASE_API_KEY,
      authDomain: process.env.NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN,
      projectId: process.env.NEXT_PUBLIC_FIREBASE_PROJECT_ID,
      appId: process.env.NEXT_PUBLIC_FIREBASE_APP_ID,
      measurementId: process.env.NEXT_PUBLIC_FIREBASE_MEASUREMENT_ID,
    });
  }
  return getApps()[0];
}

// Google Analytics (GA4) を初期化する。measurementIdが未設定の環境
// (ローカル開発など)や、Analyticsが動作しない環境では何もしない。
// firebase/analytics は動的importにして、Analyticsを使わない環境の
// クライアントバンドルに載らないようにする。
export async function initAnalytics(): Promise<void> {
  if (!process.env.NEXT_PUBLIC_FIREBASE_MEASUREMENT_ID) return;

  const { getAnalytics, isSupported } = await import('firebase/analytics');
  // Cookie無効やSSR相当の環境ではisSupported()がfalseになる
  if (!(await isSupported())) return;

  getAnalytics(getFirebaseApp());
}

// GoogleでFirebase Authにサインインし、IDトークンを返す
// (このIDトークンをNextAuthのCredentialsプロバイダに渡してサーバー側で検証する)。
export async function signInWithGooglePopup(): Promise<string> {
  const auth = getAuth(getFirebaseApp());
  const result = await signInWithPopup(auth, new GoogleAuthProvider());
  return result.user.getIdToken();
}
