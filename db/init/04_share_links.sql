-- アカウント単位でログイン不要の閲覧専用リンクを発行する仕組み。
-- token から screen_name を引いて画像一覧を返すだけで、X APIは一切呼ばない
-- （既にDB/R2に溜まっている revealed 済みの画像を配信するのみ）。
CREATE TABLE IF NOT EXISTS share_links (
    token       TEXT PRIMARY KEY,
    screen_name TEXT NOT NULL REFERENCES target_accounts (screen_name) ON DELETE CASCADE,
    created_by  TEXT NOT NULL REFERENCES app_users (email),
    revoked_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_share_links_screen_name ON share_links (screen_name)
    WHERE revoked_at IS NULL;
