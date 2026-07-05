-- cronでの新着収集を「取得はするが画面には出さない」方式に変更するための対応。
-- 取得直後は revealed=false で保存し、「最新を取得」ボタン押下時にまとめて公開する
-- （バックアップ・顔検出は revealed に関係なく先に済ませておく）。
ALTER TABLE media_assets ADD COLUMN IF NOT EXISTS revealed BOOLEAN NOT NULL DEFAULT TRUE;
CREATE INDEX IF NOT EXISTS idx_media_unrevealed ON media_assets (x_user_id) WHERE NOT revealed;

-- 新着有無は revealed=false の実件数から直接数えるようになったため不要
ALTER TABLE target_accounts DROP COLUMN IF EXISTS pending_count;
