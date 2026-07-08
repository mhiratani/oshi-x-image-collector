'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { useParams } from 'next/navigation';
import IdolImage from '@/components/IdolImage';
import { useLightboxSwipe } from '@/lib/useLightboxSwipe';
import { useLightboxHistoryBack } from '@/lib/useLightboxHistoryBack';

type MediaItem = {
  media_key: string;
  tweet_id: string;
  x_cdn_url: string;
  r2_backup_url: string | null;
  posted_at: string;
};

export default function SharedGalleryPage() {
  const { token } = useParams<{ token: string }>();
  const [screenName, setScreenName] = useState<string | null>(null);
  const [items, setItems] = useState<MediaItem[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [hasMore, setHasMore] = useState(true);
  const [loading, setLoading] = useState(false);
  const [invalid, setInvalid] = useState(false);
  const [faceOnly, setFaceOnly] = useState(false);
  const [selected, setSelected] = useState<MediaItem | null>(null);
  const sentinelRef = useRef<HTMLDivElement>(null);

  const loadMore = useCallback(
    async (reset = false) => {
      if (loading) return;
      setLoading(true);
      try {
        const params = new URLSearchParams();
        if (!reset && cursor) params.set('cursor', cursor);
        if (faceOnly) params.set('faceOnly', 'true');
        const res = await fetch(`/api/s/${token}/media?${params}`);
        if (!res.ok) {
          setInvalid(true);
          setHasMore(false);
          return;
        }
        const json = await res.json();
        setScreenName(json.screenName);
        setItems((prev) => {
          const base = reset ? [] : prev;
          const seen = new Set(base.map((i) => i.media_key));
          const fresh = (json.items as MediaItem[]).filter((i) => !seen.has(i.media_key));
          return [...base, ...fresh];
        });
        setCursor(json.nextCursor);
        setHasMore(json.hasMore);
      } finally {
        setLoading(false);
      }
      // eslint-disable-next-line react-hooks/exhaustive-deps
    },
    [cursor, faceOnly, token]
  );

  // 顔フィルター切り替え時にリセットして再取得
  useEffect(() => {
    setItems([]);
    setCursor(null);
    setHasMore(true);
    loadMore(true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [faceOnly]);

  useEffect(() => {
    const el = sentinelRef.current;
    if (!el) return;
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0].isIntersecting && hasMore && !loading) loadMore();
      },
      { rootMargin: '600px' }
    );
    observer.observe(el);
    return () => observer.disconnect();
  }, [hasMore, loading, loadMore]);

  // 拡大表示中のスワイプで前後の画像に送る
  const { handleTouchStart, handleTouchEnd, wasSwipe } = useLightboxSwipe({
    items,
    selected,
    setSelected,
    hasMore,
    loading,
    loadMore,
  });
  // 拡大表示中は「戻る」でページごと戻さず、拡大表示を閉じるだけにする
  useLightboxHistoryBack(selected !== null, () => setSelected(null));

  if (invalid) {
    return <p className="status">このリンクは無効です（発行者によって取り消されたか、存在しません）</p>;
  }

  return (
    <>
      <div className="toolbar">
        {screenName && <span className="chip active">@{screenName}</span>}
        <button
          className={`chip ${faceOnly ? 'active' : ''}`}
          onClick={() => setFaceOnly((v) => !v)}
        >
          🙂 顔のみ
        </button>
      </div>

      <div className="grid">
        {items.map((item) => (
          <button key={item.media_key} className="cell" onClick={() => setSelected(item)}>
            <IdolImage
              xCdnUrl={item.x_cdn_url}
              r2BackupUrl={item.r2_backup_url}
              altText={`@${screenName} の画像`}
            />
          </button>
        ))}
      </div>

      <div ref={sentinelRef} />
      <div className="status">
        {loading && '読み込み中…'}
        {!loading && items.length === 0 && !hasMore && <p>まだ画像がありません</p>}
        {!loading && !hasMore && items.length > 0 && <p>ストック済み {items.length} 枚をすべて表示しました</p>}
      </div>

      {selected && (
        <div
          className="lightbox"
          onClick={() => { if (!wasSwipe()) setSelected(null); }}
          onTouchStart={handleTouchStart}
          onTouchEnd={handleTouchEnd}
        >
          <IdolImage
            key={selected.media_key}
            xCdnUrl={selected.x_cdn_url}
            r2BackupUrl={selected.r2_backup_url}
            altText="拡大画像"
            size="orig"
          />
          <div className="meta" onClick={(e) => e.stopPropagation()}>
            <span>{new Date(selected.posted_at).toLocaleString('ja-JP')}</span>
            <a
              href={`https://x.com/i/web/status/${selected.tweet_id}`}
              target="_blank"
              rel="noreferrer"
            >
              元ツイートを開く ↗
            </a>
          </div>
        </div>
      )}
    </>
  );
}
