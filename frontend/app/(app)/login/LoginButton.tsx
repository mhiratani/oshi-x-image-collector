'use client';

import { useState } from 'react';
import { signIn } from 'next-auth/react';
import { signInWithGooglePopup } from '@/lib/firebaseClient';

export default function LoginButton() {
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function handleClick() {
    setError(null);
    setLoading(true);
    try {
      const idToken = await signInWithGooglePopup();
      // next-auth/reactのsignIn()を使う(CSRFトークンを自動的に扱ってくれるため、
      // /api/auth/callback/firebase への生fetchに書き換えないこと)
      await signIn('firebase', { idToken, redirectTo: '/' });
    } catch (e) {
      setError(e instanceof Error ? e.message : 'ログインに失敗しました');
      setLoading(false);
    }
  }

  return (
    <div style={{ display: 'grid', gap: '1rem', justifyItems: 'center' }}>
      <button className="primary" type="button" onClick={handleClick} disabled={loading}>
        {loading ? 'ログイン中...' : 'Googleでログイン'}
      </button>
      {error && <p style={{ color: 'crimson' }}>{error}</p>}
    </div>
  );
}
