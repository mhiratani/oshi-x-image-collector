'use client';

import { useEffect } from 'react';
import { initAnalytics } from '@/lib/firebaseClient';

// 描画するものは無く、マウント時にGA4の計測を開始するだけのコンポーネント。
// 各root layoutの<body>末尾に置く。
export default function FirebaseAnalytics() {
  useEffect(() => {
    // 計測の失敗でアプリ本体が壊れないよう握りつぶす
    initAnalytics().catch(() => {});
  }, []);

  return null;
}
