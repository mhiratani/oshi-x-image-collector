'use client';

import { useEffect, useRef } from 'react';

// 拡大表示（ライトボックス）を開いている間、ブラウザ/端末の「戻る」操作で
// ページごと戻ってしまわないよう、ライトボックスを閉じるだけに留める。
// 開いた瞬間に履歴を1つ積んでおき、popstate（戻る操作）を検知したら閉じる。
// スワイプで表示中の画像が変わるだけの間（isOpenは変化しない）は再度積まない。
export function useLightboxHistoryBack(isOpen: boolean, onClose: () => void) {
  const pushedRef = useRef(false);

  useEffect(() => {
    if (!isOpen) return;

    window.history.pushState({ lightbox: true }, '');
    pushedRef.current = true;

    const handlePopState = () => {
      pushedRef.current = false;
      onClose();
    };
    window.addEventListener('popstate', handlePopState);

    return () => {
      window.removeEventListener('popstate', handlePopState);
      // 戻る操作以外（背景クリック等）で閉じられた場合は、積んだ履歴エントリを消費しておく
      if (pushedRef.current) {
        pushedRef.current = false;
        window.history.back();
      }
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isOpen]);
}
