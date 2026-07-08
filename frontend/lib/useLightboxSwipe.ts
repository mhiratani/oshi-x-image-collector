'use client';

import { useMemo, useRef } from 'react';

type WithMediaKey = { media_key: string };

// 拡大表示（ライトボックス）中のスワイプで前後の画像に送るための共通ロジック。
// スワイプ操作の直後にブラウザが合成clickを発火させてライトボックスが閉じてしまう
// ことがあるため、wasSwipe() で直前の操作がスワイプだったかを消費的に判定できるようにしている。
export function useLightboxSwipe<T extends WithMediaKey>({
  items,
  selected,
  setSelected,
  hasMore,
  loading,
  loadMore,
}: {
  items: T[];
  selected: T | null;
  setSelected: (item: T) => void;
  hasMore: boolean;
  loading: boolean;
  loadMore: () => void;
}) {
  const touchStart = useRef<{ x: number; y: number } | null>(null);
  const swiped = useRef(false);

  const selectedIndex = useMemo(
    () => (selected ? items.findIndex((i) => i.media_key === selected.media_key) : -1),
    [items, selected]
  );

  const showPrev = () => {
    if (selectedIndex > 0) setSelected(items[selectedIndex - 1]);
  };
  const showNext = () => {
    if (selectedIndex < 0) return;
    if (selectedIndex < items.length - 1) {
      setSelected(items[selectedIndex + 1]);
    } else if (hasMore && !loading) {
      loadMore(); // 表示済みの末尾まで来ていたら追加読み込みする
    }
  };

  const handleTouchStart = (e: React.TouchEvent) => {
    // マルチタッチ（ピンチズーム等）はスワイプ判定の対象外にする
    if (e.touches.length !== 1) {
      touchStart.current = null;
      return;
    }
    touchStart.current = { x: e.touches[0].clientX, y: e.touches[0].clientY };
  };

  const handleTouchEnd = (e: React.TouchEvent) => {
    const start = touchStart.current;
    touchStart.current = null;
    if (!start || e.changedTouches.length !== 1) return;
    const dx = e.changedTouches[0].clientX - start.x;
    const dy = e.changedTouches[0].clientY - start.y;
    // 横方向の移動が閾値未満、または縦方向の移動の方が大きい場合はスワイプ送りとみなさない
    if (Math.abs(dx) < 50 || Math.abs(dx) <= Math.abs(dy)) return;
    swiped.current = true;
    if (dx < 0) showNext();
    else showPrev();
  };

  // ライトボックスのonClickから呼ぶ。直前がスワイプ操作ならtrueを返し、呼び出し側は閉じる処理を
  // スキップする（スワイプ直後の合成clickでライトボックスが閉じてしまうのを防ぐ）。
  const wasSwipe = () => {
    if (swiped.current) {
      swiped.current = false;
      return true;
    }
    return false;
  };

  return { showPrev, showNext, handleTouchStart, handleTouchEnd, wasSwipe };
}
