'use client';

import { useState } from 'react';

type Props = {
  xCdnUrl: string;
  r2BackupUrl: string | null;
  altText: string;
  size?: 'small' | 'orig';
};

// バックアップ済みならローカル(自前ディスク)を優先表示する。
// グリッドは軽量サムネイル、拡大表示はバックアップ済みオリジナルを使う。
// X CDN は「まだバックアップされていない新着」の表示と、ローカルが
// 何らかの理由で読めなかった時のフォールバックにのみ使う。
function thumbUrlFor(r2BackupUrl: string): string {
  return r2BackupUrl.replace(/(\.[^./]+)$/, '_thumb.jpg');
}

export default function IdolImage({ xCdnUrl, r2BackupUrl, altText, size = 'small' }: Props) {
  const cdnUrl = size === 'orig' ? `${xCdnUrl}?name=orig` : `${xCdnUrl}?name=small`;
  const localUrl = r2BackupUrl ? (size === 'orig' ? r2BackupUrl : thumbUrlFor(r2BackupUrl)) : null;

  const primary = localUrl ?? cdnUrl;
  const [imgSrc, setImgSrc] = useState(primary);
  const [triedFallback, setTriedFallback] = useState(false);
  const [dead, setDead] = useState(false);

  const handleError = () => {
    if (localUrl && !triedFallback) {
      setTriedFallback(true);
      setImgSrc(cdnUrl); // ローカルが読めなければX CDNへ
    } else {
      setDead(true);
    }
  };

  if (dead) {
    return <div style={{ display: 'grid', placeItems: 'center', width: '100%', height: '100%', color: '#666', fontSize: 12 }}>画像消失</div>;
  }

  return <img src={imgSrc} alt={altText} onError={handleError} loading="lazy" />;
}
