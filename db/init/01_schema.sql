-- 将来のCLIP埋め込み（セマンティック検索）用に pgvector を有効化
CREATE EXTENSION IF NOT EXISTS vector;

-- ログインユーザー(Pocket ID / OIDC)
-- emailをそのまま安定した識別子として使う（PocketIDのOIDC subが同一メールに
-- 対して毎回変わる挙動が実機で確認されたため、subをキーにするのは避けた）
CREATE TABLE IF NOT EXISTS app_users (
    email      TEXT PRIMARY KEY,
    name       TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 収集対象アカウント
-- 設計書からの変更点: screen_name を PK にし、x_user_id は NULL 許容。
-- ユーザーは screen_name だけ登録すればよく、x_user_id はワーカーが
-- X API (GET /2/users/by/username/:name) で自動解決する。
CREATE TABLE IF NOT EXISTS target_accounts (
    screen_name     TEXT PRIMARY KEY,          -- 例: fruits_zipper（@なし）
    x_user_id       TEXT UNIQUE,               -- ワーカーが自動解決
    last_fetched_id TEXT,                      -- 差分更新用 since_id
    backfill_cursor TEXT,                      -- バックフィルで遡った最古ツイートID
    backfill_done   BOOLEAN NOT NULL DEFAULT FALSE, -- 過去を掘り尽くしたら true
    pending_count   INT NOT NULL DEFAULT 0,    -- 新着チェックで見つけた未取得ポスト数(最大5)
    checked_at      TIMESTAMPTZ,               -- 最後に新着チェックした日時
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 「どのユーザーがどのアカウントを推しリストに入れているか」
-- target_accounts自体はユーザー間で共有（同じアカウントを複数人が推してもクロール・バックアップは1つで済む）。
-- 表示・追加・削除はこのテーブル経由でユーザーごとにスコープする。
CREATE TABLE IF NOT EXISTS user_subscriptions (
    user_email  TEXT NOT NULL REFERENCES app_users (email) ON DELETE CASCADE,
    screen_name TEXT NOT NULL REFERENCES target_accounts (screen_name) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_email, screen_name)
);

-- 収集画像データ
CREATE TABLE IF NOT EXISTS media_assets (
    media_key       TEXT PRIMARY KEY,          -- Xのメディア一意キー
    tweet_id        TEXT NOT NULL,
    x_user_id       TEXT NOT NULL REFERENCES target_accounts (x_user_id) ON DELETE CASCADE,
    x_cdn_url       TEXT NOT NULL,             -- pbs.twimg.com のURL（デフォルト取得元）
    r2_backup_url   TEXT,                      -- MinIOバックアップ済みURL（R2相当）
    backup_attempts INT NOT NULL DEFAULT 0,    -- バックアップ失敗回数（5回で打ち切り）
    posted_at       TIMESTAMPTZ NOT NULL,
    ml_tags         JSONB,                     -- 将来のAI推論結果
    embedding       vector(512),               -- 将来のCLIP特徴ベクトル
    is_face         BOOLEAN,                   -- 顔フィルター: NULL=未判定
    face_confidence REAL,                      -- BlazeFaceの検出スコア(最大値)
    face_reviewed   BOOLEAN NOT NULL DEFAULT FALSE, -- 手動で上書き済みなら自動再判定でスキップ
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_media_posted_at ON media_assets (posted_at DESC, media_key DESC);
CREATE INDEX IF NOT EXISTS idx_media_user ON media_assets (x_user_id, posted_at DESC);
CREATE INDEX IF NOT EXISTS idx_media_backup_pending ON media_assets (posted_at DESC)
    WHERE r2_backup_url IS NULL;
CREATE INDEX IF NOT EXISTS idx_media_ml_tags ON media_assets USING gin (ml_tags);
CREATE INDEX IF NOT EXISTS idx_media_is_face ON media_assets (is_face) WHERE is_face IS NOT NULL;
