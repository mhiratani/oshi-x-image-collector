'use client';

import { useEffect, useRef } from 'react';

// 「開いている/有効になっている何か」（ライトボックスやフィルター）を、ブラウザ/端末の
// 「戻る」操作でページごと戻さずに閉じる・解除するだけに留めるための汎用フック。
// 有効になった瞬間に履歴を1つ積んでおき、popstate（戻る操作）を検知したらonBackを呼ぶ。
// 有効中に内容が変わるだけの間（isActiveは変化しない）は再度積まない。
//
// markerは履歴エントリに埋め込む識別子。複数のフックを同時に使う場合
// （例: フィルター有効中にライトボックスを開く）、戻る操作で自分より上に積まれた
// エントリが消費されただけのpopstateを無視するために使う。
export function useHistoryBackClose(marker: string, isActive: boolean, onBack: () => void) {
  const pushedRef = useRef(false);
  // onBackは毎レンダーで作り直されるため、effectを張り直さずに最新を参照する
  const onBackRef = useRef(onBack);
  onBackRef.current = onBack;

  useEffect(() => {
    if (!isActive) return;

    // Next.jsが履歴エントリに保持している内部stateを引き継ぎつつ、自分のマーカーを足す
    window.history.pushState({ ...window.history.state, [marker]: true }, '');
    pushedRef.current = true;

    const handlePopState = (event: PopStateEvent) => {
      // 自分のマーカーが残っているエントリに着地した場合は、自分より上に積まれた
      // 別フックのエントリ（例: ライトボックス）が消費されただけなので何もしない
      if (event.state && event.state[marker]) return;
      pushedRef.current = false;
      onBackRef.current();
    };
    window.addEventListener('popstate', handlePopState);

    return () => {
      window.removeEventListener('popstate', handlePopState);
      // 戻る操作以外（背景クリックやチップ操作等）で解除された場合は、積んだ履歴エントリを消費しておく。
      // ただし別ページへの遷移でアンマウントされた場合（現在のエントリに自分のマーカーが無い）は、
      // back()すると遷移先から戻ってしまうため何もしない
      if (pushedRef.current) {
        pushedRef.current = false;
        if (window.history.state && window.history.state[marker]) {
          window.history.back();
        }
      }
    };
  }, [isActive, marker]);
}
