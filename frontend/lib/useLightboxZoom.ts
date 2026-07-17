'use client';

import { useEffect, useRef, useState } from 'react';

type Transform = { scale: number; tx: number; ty: number };

const IDENTITY: Transform = { scale: 1, tx: 0, ty: 0 };
const MAX_SCALE = 5;
// 指を離した時にこれ以下なら等倍へスナップする（ピンチイン終わりの微妙なズレを吸収）
const SNAP_SCALE = 1.05;
// ダブルタップ判定: 1タップの押下時間と2タップの間隔の上限、タップとみなす指の移動量と
// 2タップ間の位置ズレの上限
const DOUBLE_TAP_MS = 300;
const TAP_SLOP_PX = 12;
const DOUBLE_TAP_SLOP_PX = 50;

type Gesture = {
  mode: 'pinch' | 'pan';
  // ズーム変形前の画像ラッパーの中心とサイズ（クランプとピンチ中心の計算に使う）
  baseCx: number;
  baseCy: number;
  baseW: number;
  baseH: number;
  // pinch: 開始時の2本指間距離とスケール、指の中点直下にある画像上の点
  startDist: number;
  startScale: number;
  contentX: number;
  contentY: number;
  // pan: 開始時のタッチ位置と移動量
  startX: number;
  startY: number;
  startTx: number;
  startTy: number;
};

// 拡大表示（ライトボックス）中のピンチイン/アウトによるズームと、ズーム中の1本指ドラッグでの
// 表示位置移動、ズーム中のダブルタップによる等倍への復帰。
// CSSの .lightbox { touch-action: none } と組み合わせて使う（ブラウザ既定の
// ページ全体ズームを止め、画像だけを変形させるため）。
// スワイプ送り（useLightboxSwipe）とは独立しており、呼び出し側でズーム中はスワイプ送りと
// タップで閉じる処理をスキップする。
export function useLightboxZoom(resetKey: string | null) {
  const [transform, setTransform] = useState<Transform>(IDENTITY);
  const transformRef = useRef(transform);
  transformRef.current = transform;

  const targetRef = useRef<HTMLDivElement>(null);
  const gesture = useRef<Gesture | null>(null);
  // 最後にズーム/パン操作をした時刻。ジェスチャー直後の合成clickだけを抑止するために使う
  // （フラグ方式だと合成clickを発火しないブラウザでフラグが残り、次の正当なタップを潰してしまう）
  const gesturedAt = useRef(0);
  // ズーム中のダブルタップで等倍に戻すためのタップ追跡。tapは現在の1本指タッチが
  // タップのままか（動きすぎたら無効化）、lastTapは直前に成立したタップの位置と離した時刻
  const tap = useRef<{ x: number; y: number; startedAt: number; valid: boolean } | null>(null);
  const lastTap = useRef<{ x: number; y: number; endedAt: number } | null>(null);

  // 表示画像が切り替わったらズームをリセットする
  useEffect(() => {
    setTransform(IDENTITY);
    gesture.current = null;
    tap.current = null;
    lastTap.current = null;
  }, [resetKey]);

  const clamp = (v: number, max: number) => Math.min(max, Math.max(-max, v));

  // 拡大した画像の端が元の表示枠の内側に入り込まない範囲に移動量を収める
  const clampTransform = (t: Transform, g: Gesture): Transform => ({
    scale: t.scale,
    tx: clamp(t.tx, ((t.scale - 1) * g.baseW) / 2),
    ty: clamp(t.ty, ((t.scale - 1) * g.baseH) / 2),
  });

  // 現在の変形量を差し引いた、画像ラッパー本来の中心とサイズを測る
  const measureBase = () => {
    const el = targetRef.current;
    if (!el) return null;
    const rect = el.getBoundingClientRect();
    const { scale, tx, ty } = transformRef.current;
    return {
      baseCx: rect.left + rect.width / 2 - tx,
      baseCy: rect.top + rect.height / 2 - ty,
      baseW: rect.width / scale,
      baseH: rect.height / scale,
    };
  };

  const startPinch = (a: React.Touch, b: React.Touch) => {
    const base = measureBase();
    if (!base) return;
    const { scale, tx, ty } = transformRef.current;
    const midX = (a.clientX + b.clientX) / 2;
    const midY = (a.clientY + b.clientY) / 2;
    gesture.current = {
      mode: 'pinch',
      ...base,
      startDist: Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY),
      startScale: scale,
      // ピンチ中心直下の画像上の点。ズーム中もこの点が指の中点に留まるように移動量を決める
      contentX: (midX - base.baseCx - tx) / scale,
      contentY: (midY - base.baseCy - ty) / scale,
      startX: 0,
      startY: 0,
      startTx: 0,
      startTy: 0,
    };
  };

  const startPan = (t: React.Touch) => {
    const base = measureBase();
    if (!base) return;
    gesture.current = {
      mode: 'pan',
      ...base,
      startDist: 0,
      startScale: 0,
      contentX: 0,
      contentY: 0,
      startX: t.clientX,
      startY: t.clientY,
      startTx: transformRef.current.tx,
      startTy: transformRef.current.ty,
    };
  };

  const handleTouchStart = (e: React.TouchEvent) => {
    if (e.touches.length === 1) {
      const t = e.touches[0];
      tap.current = { x: t.clientX, y: t.clientY, startedAt: Date.now(), valid: true };
    } else {
      // 2本目の指が付いたらタップ扱いをやめ、ダブルタップの連鎖も切る
      tap.current = null;
      lastTap.current = null;
    }
    if (e.touches.length === 2) {
      startPinch(e.touches[0], e.touches[1]);
    } else if (e.touches.length === 1 && transformRef.current.scale > 1) {
      startPan(e.touches[0]);
    } else {
      gesture.current = null;
    }
  };

  const handleTouchMove = (e: React.TouchEvent) => {
    const c = tap.current;
    if (c && e.touches.length === 1) {
      const t = e.touches[0];
      if (Math.hypot(t.clientX - c.x, t.clientY - c.y) > TAP_SLOP_PX) c.valid = false;
    }
    const g = gesture.current;
    if (!g) return;
    if (g.mode === 'pinch' && e.touches.length >= 2) {
      const [a, b] = [e.touches[0], e.touches[1]];
      const dist = Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY);
      if (g.startDist === 0) return;
      const scale = Math.min(MAX_SCALE, Math.max(1, (g.startScale * dist) / g.startDist));
      const midX = (a.clientX + b.clientX) / 2;
      const midY = (a.clientY + b.clientY) / 2;
      gesturedAt.current = Date.now();
      setTransform(
        clampTransform(
          {
            scale,
            tx: midX - g.baseCx - g.contentX * scale,
            ty: midY - g.baseCy - g.contentY * scale,
          },
          g
        )
      );
    } else if (g.mode === 'pan' && e.touches.length === 1) {
      const t = e.touches[0];
      gesturedAt.current = Date.now();
      setTransform(
        clampTransform(
          {
            scale: transformRef.current.scale,
            tx: g.startTx + (t.clientX - g.startX),
            ty: g.startTy + (t.clientY - g.startY),
          },
          g
        )
      );
    }
  };

  // 最後の指が離れた時点でタップ成立を判定し、ズーム中のダブルタップなら等倍へ戻す
  const handleTapEnd = () => {
    const c = tap.current;
    tap.current = null;
    const now = Date.now();
    if (!c || !c.valid || now - c.startedAt > DOUBLE_TAP_MS) return;
    const prev = lastTap.current;
    lastTap.current = { x: c.x, y: c.y, endedAt: now };
    if (
      prev &&
      now - prev.endedAt < DOUBLE_TAP_MS &&
      Math.hypot(c.x - prev.x, c.y - prev.y) < DOUBLE_TAP_SLOP_PX &&
      transformRef.current.scale > 1
    ) {
      setTransform(IDENTITY);
      lastTap.current = null;
      // リセット直後の合成clickでライトボックスが閉じないようにジェスチャー扱いにする
      gesturedAt.current = now;
    }
  };

  const handleTouchEnd = (e: React.TouchEvent) => {
    if (e.touches.length === 0) {
      gesture.current = null;
      handleTapEnd();
      // ほぼ等倍まで戻していたら完全にリセットする
      if (transformRef.current.scale < SNAP_SCALE) setTransform(IDENTITY);
    } else if (e.touches.length === 1) {
      // ピンチの片指だけ離した場合は、残った指でのドラッグ移動に引き継ぐ
      if (transformRef.current.scale > 1) startPan(e.touches[0]);
      else gesture.current = null;
    } else if (e.touches.length === 2) {
      // 3本目の指が離れた等。残り2本でピンチを取り直す
      startPinch(e.touches[0], e.touches[1]);
    }
  };

  // ライトボックスのonClickから呼ぶ。直前(500ms以内)の操作がズーム/パンならtrueを返し、
  // 呼び出し側は閉じる処理をスキップする（ジェスチャー直後の合成clickで閉じてしまうのを防ぐ）。
  const wasGesture = () => {
    const recent = Date.now() - gesturedAt.current < 500;
    gesturedAt.current = 0;
    return recent;
  };

  return {
    targetRef,
    style: {
      transform: `translate(${transform.tx}px, ${transform.ty}px) scale(${transform.scale})`,
    } as React.CSSProperties,
    isZoomed: transform.scale > 1,
    // イベントハンドラ内から最新のズーム状態を同期的に読むためのアクセサ
    isZoomedNow: () => transformRef.current.scale > 1,
    handleTouchStart,
    handleTouchMove,
    handleTouchEnd,
    wasGesture,
  };
}
