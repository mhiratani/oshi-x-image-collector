'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';

type WithMediaKey = { media_key: string };

// 指を離したときにページ送りを確定する条件（幅に対する移動量の割合 / 速いスワイプの速度）
const COMMIT_RATIO = 0.25;
const FLING_VELOCITY = 0.4; // px/ms
const FLING_MIN_PX = 16;
// 縦横どちらの操作かを決めるのに必要な移動量
const DIRECTION_SLOP_PX = 8;
// 送り先が無い方向はこの割合しか動かさない（端まで来たことが分かる手応え）
const EDGE_RESISTANCE = 0.3;
// 指を離してからページが収まるまでの時間
const SNAP_MS = 220;
// 隣のページとの間隔
const PAGE_GAP_PX = 16;

export type LightboxPage<T> = {
  key: string;
  item: T;
  /** 前(-1)・現在(0)・次(+1)のページを横並びに置くための位置 */
  style: React.CSSProperties;
};

export type LightboxPagerApi<T> = {
  containerRef: React.RefObject<HTMLDivElement>;
  trackStyle: React.CSSProperties;
  pages: LightboxPage<T>[];
  currentKey: string | null;
  handleTouchStart: (e: React.TouchEvent) => void;
  handleTouchMove: (e: React.TouchEvent) => void;
  handleTouchEnd: (e: React.TouchEvent) => void;
  wasSwipe: () => boolean;
};

type Drag = {
  startX: number;
  startY: number;
  lastX: number;
  lastT: number;
  velocity: number;
  axis: 'undecided' | 'x' | 'y';
};

// 拡大表示（ライトボックス）中の画像送り。Androidアプリの HorizontalPager と同じ操作感になるよう、
// 前後の画像を左右に並べたトラックを指の動きに追従させ、指を離したところで隣のページへ収める
// （移動量が足りなければ元のページへ戻す）。
// スワイプ操作の直後にブラウザが合成clickを発火させてライトボックスが閉じてしまうことがあるため、
// wasSwipe() で直前の操作がスワイプだったかを消費的に判定できるようにしている。
// ズーム中（useLightboxZoom）はページ送りをせず、1本指ドラッグを表示位置の移動に譲る。
export function useLightboxPager<T extends WithMediaKey>({
  items,
  selected,
  setSelected,
  hasMore,
  loading,
  loadMore,
  isZoomedNow,
}: {
  items: T[];
  selected: T | null;
  setSelected: (item: T) => void;
  hasMore: boolean;
  loading: boolean;
  loadMore: () => void;
  isZoomedNow: () => boolean;
}): LightboxPagerApi<T> {
  const containerRef = useRef<HTMLDivElement>(null);
  // dx: トラックの現在の移動量。snapping: 指を離した後の収まりアニメーション中か
  const [dx, setDx] = useState(0);
  const [snapping, setSnapping] = useState(false);

  const drag = useRef<Drag | null>(null);
  // 収まりアニメーションの完了後に表示を切り替える画像
  const pending = useRef<T | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  // 最後に横スワイプをした時刻。スワイプ直後の合成clickだけを抑止するために使う
  // （フラグ方式だと合成clickを発火しないブラウザでフラグが残り、次の正当なタップを潰してしまう）
  const swipedAt = useRef(0);

  const selectedIndex = useMemo(
    () => (selected ? items.findIndex((i) => i.media_key === selected.media_key) : -1),
    [items, selected]
  );

  const neighbor = (dir: 1 | -1): T | null =>
    selectedIndex < 0 ? null : items[selectedIndex + dir] ?? null;

  // 1ページ分の移動量（ページ幅 + 間隔）
  const pageStride = () =>
    (containerRef.current?.clientWidth ?? window.innerWidth) + PAGE_GAP_PX;

  // 収まりアニメーションの完了時（または次の操作で割り込まれた時）にページ送りを確定する
  const settle = useCallback(() => {
    if (timer.current) clearTimeout(timer.current);
    timer.current = null;
    const next = pending.current;
    pending.current = null;
    setSnapping(false);
    setDx(0);
    if (next) setSelected(next);
  }, [setSelected]);

  // ライトボックスを閉じたら進行中の操作を捨てる（確定タイマーが後から発火して再表示されないように）
  useEffect(() => {
    if (selected) return;
    if (timer.current) clearTimeout(timer.current);
    timer.current = null;
    pending.current = null;
    drag.current = null;
    setSnapping(false);
    setDx(0);
  }, [selected]);

  useEffect(() => () => {
    if (timer.current) clearTimeout(timer.current);
  }, []);

  const handleTouchStart = (e: React.TouchEvent) => {
    // アニメーション中に触られたら、その送りを確定してから新しい操作を受け付ける
    if (timer.current) settle();
    // マルチタッチ（ピンチズーム等）とズーム中はページ送りの対象外
    if (e.touches.length !== 1 || isZoomedNow()) {
      drag.current = null;
      return;
    }
    const t = e.touches[0];
    drag.current = {
      startX: t.clientX,
      startY: t.clientY,
      lastX: t.clientX,
      lastT: Date.now(),
      velocity: 0,
      axis: 'undecided',
    };
  };

  const handleTouchMove = (e: React.TouchEvent) => {
    const d = drag.current;
    if (!d) return;
    // 途中でピンチに移った場合はページ送りを中止し、トラックを元の位置へ戻す
    if (e.touches.length !== 1 || isZoomedNow()) {
      drag.current = null;
      setDx(0);
      return;
    }
    const t = e.touches[0];
    const mx = t.clientX - d.startX;
    const my = t.clientY - d.startY;
    if (d.axis === 'undecided') {
      if (Math.hypot(mx, my) < DIRECTION_SLOP_PX) return;
      d.axis = Math.abs(mx) > Math.abs(my) ? 'x' : 'y';
    }
    if (d.axis !== 'x') return;
    const now = Date.now();
    if (now > d.lastT) {
      d.velocity = (t.clientX - d.lastX) / (now - d.lastT);
      d.lastX = t.clientX;
      d.lastT = now;
    }
    swipedAt.current = now;
    const resist = neighbor(mx < 0 ? 1 : -1) === null;
    setDx(resist ? mx * EDGE_RESISTANCE : mx);
  };

  const handleTouchEnd = (e: React.TouchEvent) => {
    const d = drag.current;
    drag.current = null;
    if (!d || d.axis !== 'x' || e.changedTouches.length !== 1) return;
    const mx = e.changedTouches[0].clientX - d.startX;
    const dir: 1 | -1 = mx < 0 ? 1 : -1;
    // 速いスワイプなら移動量が閾値に届かなくても送る（指を離す直前の向きが一致している場合のみ）
    const fling =
      Math.abs(d.velocity) > FLING_VELOCITY &&
      Math.abs(mx) > FLING_MIN_PX &&
      (d.velocity < 0 ? 1 : -1) === dir;
    const commit = Math.abs(mx) > pageStride() * COMMIT_RATIO || fling;
    const target = commit ? neighbor(dir) : null;
    setSnapping(true);
    if (target) {
      pending.current = target;
      setDx(dir === 1 ? -pageStride() : pageStride());
    } else {
      // 表示済みの末尾から先へ送ろうとしたら追加読み込みする
      if (commit && dir === 1 && hasMore && !loading) loadMore();
      setDx(0);
    }
    timer.current = setTimeout(settle, SNAP_MS);
  };

  // ライトボックスのonClickから呼ぶ。直前(500ms以内)の操作が横スワイプならtrueを返し、呼び出し側は
  // 閉じる処理をスキップする（スワイプ直後の合成clickでライトボックスが閉じてしまうのを防ぐ）。
  const wasSwipe = () => {
    const recent = Date.now() - swipedAt.current < 500;
    swipedAt.current = 0;
    return recent;
  };

  // 前後1枚だけを描画する（Androidの HorizontalPager と同じく、送り先だけ先に用意しておく）
  const pages = useMemo<LightboxPage<T>[]>(() => {
    if (!selected) return [];
    if (selectedIndex < 0) {
      return [{ key: selected.media_key, item: selected, style: {} }];
    }
    return ([-1, 0, 1] as const).flatMap((offset) => {
      const item = items[selectedIndex + offset];
      if (!item) return [];
      return [
        {
          key: item.media_key,
          item,
          style: {
            transform: `translate3d(calc(${offset * 100}% + ${offset * PAGE_GAP_PX}px), 0, 0)`,
          },
        },
      ];
    });
  }, [items, selected, selectedIndex]);

  return {
    containerRef,
    trackStyle: {
      transform: `translate3d(${dx}px, 0, 0)`,
      transition: snapping ? `transform ${SNAP_MS}ms cubic-bezier(0.2, 0, 0, 1)` : 'none',
    },
    pages,
    currentKey: selected?.media_key ?? null,
    handleTouchStart,
    handleTouchMove,
    handleTouchEnd,
    wasSwipe,
  };
}
