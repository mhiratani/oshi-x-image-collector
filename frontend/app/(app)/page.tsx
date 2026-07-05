'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import IdolImage from '@/components/IdolImage';

type MediaItem = {
  media_key: string;
  tweet_id: string;
  x_user_id: string;
  x_cdn_url: string;
  r2_backup_url: string | null;
  posted_at: string;
  screen_name: string | null;
  is_face: boolean | null;
};

type Account = {
  screen_name: string;
  x_user_id: string | null;
  media_count: number;
};

type BackfillStatus = {
  running: boolean;
  allDone: boolean;
  lastError?: string | null;
};

type CollectStatus = {
  running: boolean;
  totalPending: number;
  needsInitial?: number;
  unresolved?: number;
  lastError?: string | null;
};

// X APIの生エラーをユーザー向けメッセージに変換
function friendlyApiError(msg: string): string {
  if (msg.includes('CreditsDepleted'))
    return 'X APIのクレジットが不足しています。開発者ポータルでクレジットを追加してください。取得済みの分までは保存されています。';
  if (msg.includes('UsageCapExceeded'))
    return 'X APIの月間利用上限に達しています。プランの確認が必要です。';
  if (msg.includes('rate limited') || msg.includes('429'))
    return 'X APIのレート制限中です。しばらく待ってから再実行してください。';
  if (msg.includes('client-not-enrolled'))
    return 'X開発者AppがProjectに紐付いていません。開発者ポータルで設定してください。';
  if (msg.includes('401'))
    return 'X APIの認証に失敗しました。Bearer Tokenを確認してください。';
  return `X APIエラー: ${msg.slice(0, 160)}`;
}

export default function GalleryPage() {
  const [items, setItems] = useState<MediaItem[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [hasMore, setHasMore] = useState(true);
  const [loading, setLoading] = useState(false);
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [filter, setFilter] = useState<string[]>([]);
  const [faceOnly, setFaceOnly] = useState(false);
  const [selected, setSelected] = useState<MediaItem | null>(null);
  const [backfill, setBackfill] = useState<BackfillStatus>({
    running: false,
    allDone: true,
  });
  const [backfilling, setBackfilling] = useState(false);
  const [collectStatus, setCollectStatus] = useState<CollectStatus>({
    running: false,
    totalPending: 0,
  });
  const [collecting, setCollecting] = useState(false);
  const [revealing, setRevealing] = useState(false);
  const sentinelRef = useRef<HTMLDivElement>(null);

  // バックフィルはアカウント単位の操作のため、1件だけ絞り込み中のときだけそのアカウントを対象にする
  const backfillAccount = filter.length === 1 ? filter[0] : undefined;

  const loadMore = useCallback(
    async (reset = false) => {
      if (loading) return;
      setLoading(true);
      try {
        const params = new URLSearchParams();
        if (!reset && cursor) params.set('cursor', cursor);
        if (filter.length > 0) params.set('account', filter.join(','));
        if (faceOnly) params.set('faceOnly', 'true');
        const res = await fetch(`/api/media?${params}`);
        const json = await res.json();
        setItems((prev) => {
          const base = reset ? [] : prev;
          const seen = new Set(base.map((i) => i.media_key));
          const fresh = (json.items as MediaItem[]).filter(
            (i) => !seen.has(i.media_key)
          );
          return [...base, ...fresh];
        });
        // 0件のときは既存カーソルを維持（バックフィル後の続き読みに使う）
        if (json.nextCursor) setCursor(json.nextCursor);
        setHasMore(json.hasMore);
      } finally {
        setLoading(false);
      }
    },
    [cursor, filter, faceOnly, loading]
  );

  // フィルタ変更時にリセットして再取得
  useEffect(() => {
    setItems([]);
    setCursor(null);
    setHasMore(true);
    loadMore(true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filter, faceOnly]);

  useEffect(() => {
    fetch('/api/accounts')
      .then((r) => r.json())
      .then((j) => setAccounts(j.accounts ?? []));
  }, []);

  // 絞り込みユーザーが変わるたびに、そのユーザーのバックフィル状況を取り直す
  useEffect(() => {
    const params = new URLSearchParams();
    if (backfillAccount) params.set('account', backfillAccount);
    fetch(`/api/backfill?${params}`)
      .then((r) => r.json())
      .then(setBackfill)
      .catch(() => {});
  }, [filter]);

  // 新着チェック結果（cronがDBに記録したもの）を定期的に反映
  useEffect(() => {
    const refresh = () =>
      fetch('/api/collect')
        .then((r) => r.json())
        .then(setCollectStatus)
        .catch(() => {});
    refresh();
    const timer = setInterval(refresh, 60_000);
    return () => clearInterval(timer);
  }, []);

  // 無限スクロール
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

  // 「取得を開始」(初回クロール未実行アカウント向け): 実際にX APIを呼んで収集する
  const startCollect = async () => {
    setCollecting(true);
    const res = await fetch('/api/collect', { method: 'POST' }).catch(() => null);
    if (!res || (!res.ok && res.status !== 409)) {
      setCollecting(false);
      return;
    }
    const timer = setInterval(async () => {
      const s: CollectStatus | null = await fetch('/api/collect')
        .then((r) => r.json())
        .catch(() => null);
      if (s && !s.running) {
        clearInterval(timer);
        setCollectStatus(s);
        setCollecting(false);
        // 新着はグリッド先頭に入るので最初から読み直す
        setItems([]);
        setCursor(null);
        setHasMore(true);
        loadMore(true);
        fetch('/api/accounts')
          .then((r) => r.json())
          .then((j) => setAccounts(j.accounts ?? []));
      }
    }, 3000);
  };

  // 「最新を取得」(新着バナー側): cronが裏で取得・保存済みの画像をまとめて公開するだけ。
  // X APIは呼ばないので即座に終わる
  const startReveal = async () => {
    setRevealing(true);
    await fetch('/api/reveal', { method: 'POST' }).catch(() => null);
    setRevealing(false);
    setItems([]);
    setCursor(null);
    setHasMore(true);
    loadMore(true);
    fetch('/api/collect')
      .then((r) => r.json())
      .then(setCollectStatus)
      .catch(() => {});
  };

  // 「過去を読み込む」: ワーカーのバックフィルを起動し、完了を待って続きを表示
  // ユーザーを1件だけ絞り込み中の場合はそのアカウントだけを対象にする
  const startBackfill = async () => {
    setBackfilling(true);
    const res = await fetch('/api/backfill', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ account: backfillAccount }),
    }).catch(() => null);
    if (!res || (!res.ok && res.status !== 409)) {
      setBackfilling(false);
      return;
    }
    const params = new URLSearchParams();
    if (backfillAccount) params.set('account', backfillAccount);
    const timer = setInterval(async () => {
      const s: BackfillStatus | null = await fetch(`/api/backfill?${params}`)
        .then((r) => r.json())
        .catch(() => null);
      if (s && !s.running) {
        clearInterval(timer);
        setBackfill(s);
        setBackfilling(false);
        setHasMore(true); // 番兵が見えていれば自動で続きが読み込まれる
      }
    }, 3000);
  };

  const lastError = collectStatus.lastError ?? backfill.lastError;
  const busy = backfilling || backfill.running || collecting || collectStatus.running || revealing;

  // 顔フィルターの手動上書き（拡大表示から）
  const toggleFace = async (item: MediaItem, isFace: boolean) => {
    const res = await fetch(`/api/media/${item.media_key}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ isFace }),
    }).catch(() => null);
    if (!res || !res.ok) return;
    setItems((prev) =>
      prev.map((i) => (i.media_key === item.media_key ? { ...i, is_face: isFace } : i))
    );
    setSelected((prev) => (prev && prev.media_key === item.media_key ? { ...prev, is_face: isFace } : prev));
  };

  return (
    <>
      {lastError && !busy && (
        <div className="banner-error" title={lastError}>
          ⚠ {friendlyApiError(lastError)}
        </div>
      )}
      {(collecting || collectStatus.running) && (
        <div className="banner-info">⬇ ポストを取得中…</div>
      )}
      {!busy && ((collectStatus.needsInitial ?? 0) > 0 || (collectStatus.unresolved ?? 0) > 0) && (
        <div className="banner-info">
          <span>
            🆕 まだ取得していないアカウントがあります（
            {(collectStatus.needsInitial ?? 0) + (collectStatus.unresolved ?? 0)}件）
          </span>
          <button className="primary" onClick={startCollect}>
            取得を開始
          </button>
        </div>
      )}
      {!busy && collectStatus.totalPending > 0 && (
        <div className="banner-info">
          <span>✨ 新着ポストがあります（{collectStatus.totalPending}件）</span>
          <button className="primary" onClick={startReveal}>
            最新を取得
          </button>
        </div>
      )}
      <div className="toolbar">
        <button
          className={`chip ${filter.length === 0 ? 'active' : ''}`}
          onClick={() => setFilter([])}
        >
          すべて
        </button>
        {accounts
          .filter((a) => a.x_user_id)
          .map((a) => (
            <button
              key={a.screen_name}
              className={`chip ${filter.includes(a.x_user_id!) ? 'active' : ''}`}
              onClick={() =>
                setFilter((prev) =>
                  prev.includes(a.x_user_id!)
                    ? prev.filter((id) => id !== a.x_user_id)
                    : [...prev, a.x_user_id!]
                )
              }
            >
              @{a.screen_name} ({a.media_count})
            </button>
          ))}
        <button
          className={`chip ${faceOnly ? 'active' : ''}`}
          onClick={() => setFaceOnly((v) => !v)}
        >
          🙂 顔のみ
        </button>
      </div>

      <div className="grid">
        {items.map((item) => (
          <button
            key={item.media_key}
            className="cell"
            onClick={() => setSelected(item)}
          >
            <IdolImage
              xCdnUrl={item.x_cdn_url}
              r2BackupUrl={item.r2_backup_url}
              altText={`@${item.screen_name ?? item.x_user_id} の画像`}
            />
            {filter.length !== 1 && item.screen_name && <span className="badge">@{item.screen_name}</span>}
          </button>
        ))}
      </div>

      <div ref={sentinelRef} />
      <div className="status">
        {loading && '読み込み中…'}
        {!loading && items.length === 0 && !hasMore && !backfilling && (
          <p>まだ画像がありません。アカウント管理から収集対象を登録してください。</p>
        )}
        {!loading && !hasMore && items.length > 0 && (
          <p>ストック済み {items.length} 枚をすべて表示しました</p>
        )}
        {!loading && !hasMore && (
          <div style={{ marginTop: 12 }}>
            {backfilling || backfill.running ? (
              <p>🕰 X APIから過去の投稿を取得中…（最大500ツイート分・1分ほどかかります）</p>
            ) : filter.length > 1 ? (
              <p>複数アカウントを選択中は過去の投稿を読み込めません。1つに絞り込んでください。</p>
            ) : backfill.allDone ? (
              accounts.length > 0 && <p>これ以上遡れる過去の投稿はありません</p>
            ) : (
              <button className="primary" onClick={startBackfill}>
                🕰 過去の投稿をさらに読み込む
              </button>
            )}
          </div>
        )}
      </div>

      {selected && (
        <div className="lightbox" onClick={() => setSelected(null)}>
          <IdolImage
            xCdnUrl={selected.x_cdn_url}
            r2BackupUrl={selected.r2_backup_url}
            altText="拡大画像"
            size="orig"
          />
          <div className="meta" onClick={(e) => e.stopPropagation()}>
            <span>@{selected.screen_name}</span>
            <span>{new Date(selected.posted_at).toLocaleString('ja-JP')}</span>
            <a
              href={`https://x.com/i/web/status/${selected.tweet_id}`}
              target="_blank"
              rel="noreferrer"
            >
              元ツイートを開く ↗
            </a>
            <button
              className="chip"
              onClick={() => toggleFace(selected, !selected.is_face)}
            >
              {selected.is_face ? '🙅 顔画像ではない、に変更' : '🙂 顔画像として扱う、に変更'}
            </button>
          </div>
        </div>
      )}
    </>
  );
}
