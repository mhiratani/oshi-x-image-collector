-- X API の利用状況・コスト記録
-- X APIは従量課金（返却されたリソース件数に応じた課金、サブスクリプションなし）のため、
-- 呼び出しごとに「何件分のリソースを読んだか」を記録し、単価を掛けてコストを算出する。
-- 単価は https://docs.x.com/x-api/getting-started/pricing 記載の基準(2026年時点)。
CREATE TABLE IF NOT EXISTS api_usage_log (
    id            BIGSERIAL PRIMARY KEY,
    called_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    purpose       TEXT NOT NULL,          -- resolve / check / collect / backfill
    endpoint      TEXT NOT NULL,          -- 呼び出したエンドポイント（例: users/:id/tweets）
    screen_name   TEXT,                   -- 対象アカウント（アカウント非依存の呼び出しはNULL）
    resource      TEXT NOT NULL,          -- 課金区分（posts_read / user_read）
    quantity      INT NOT NULL,           -- 返却されたリソース件数（課金対象数）
    unit_cost_usd NUMERIC(10,4) NOT NULL, -- 呼び出し時点の1件あたり単価(USD)
    cost_usd      NUMERIC(12,6) NOT NULL  -- quantity * unit_cost_usd
);

CREATE INDEX IF NOT EXISTS idx_api_usage_called_at ON api_usage_log (called_at DESC);
CREATE INDEX IF NOT EXISTS idx_api_usage_screen_name ON api_usage_log (screen_name);
