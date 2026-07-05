# oshi-x-image-collector

X（Twitter）の指定アカウントから画像を定期収集し、バックアップ・顔検出まで行うアプリ。

## 構成

- `frontend/` : Next.js アプリ（画面 + cronバッチワーカーを同一プロセスで実行）
- `db/init/01_schema.sql` : Postgres スキーマ定義（pgvector拡張を使用）
- `db/init/02_api_usage.sql` : X API呼び出しのコスト記録テーブル
- `db/init/03_media_reveal.sql` : cron収集分を「最新を取得」ボタンで公開する仕組み（media_assets.revealed）
- `db/init/04_share_links.sql` : アカウント単位のログイン不要な共有リンク（share_links）

`docker-compose.yml` には `frontend` サービスしか含まれていません。Postgres はこのリポジトリの管理外で、既存の外部DBサーバーに接続する構成です。

画像バックアップの実体は **Cloudflare R2**（S3互換オブジェクトストレージ）。
- `frontend/lib/r2.ts` : R2用のS3クライアント（`@aws-sdk/client-s3`）
- `frontend/worker/backup.js` : X CDNから取得した画像・サムネイルをR2へアップロード
- `frontend/app/backups/[...path]/route.ts` : `/backups/<x_user_id>/<media_key>.<ext>` へのリクエストをR2から都度取得して配信するプロキシ（R2の認証情報はサーバー側のみで保持し、バケット自体は非公開のままでよい）

## 初回セットアップ

### 1. .env を作成

```
cp .env.example .env
```

`.env` の各値（`X_BEARER_TOKEN`, `PG_*`, `AUTH_*`, `OIDC_*`, `CLOUDFLARE_*`）を埋める。

### 2. R2の準備（画像バックアップ用）

1. [Cloudflareダッシュボード](https://dash.cloudflare.com/) → **R2 object storage** でバケットを新規作成
2. 作成したバケットの画面から「API トークンを管理」→ 「Create Account/User API token」
   - 権限: **Object Read & Write**
   - 適用範囲: **Apply to specific buckets only** で作成したバケットのみを選択
3. 発行後に表示される値を `.env` に設定する（Secretは再表示されないのでこの時にコピーしておく）
   - `CLOUDFLARE_R2_BUCKET_NAME` : バケット名
   - `CLOUDFLARE_ACCOUNT_ID` : アカウントID（R2 Overview画面の「アカウント詳細」に表示）
   - `CLOUDFLARE_ACCOUNT_ACCESS_KEY_ID` / `CLOUDFLARE_ACCOUNT_API_SECRET` : 発行したトークンのAccess Key ID / Secret Access Key
   - `CLOUDFLARE_ACCOUNT_ENDPOINT` : `https://<ACCOUNT_ID>.r2.cloudflarestorage.com`

バケットは非公開のままでよい（アプリが `/backups/*` 経由でR2から取得してプロキシ配信するため、R2への直接公開URLは不要）。

既存のローカル `backup-storage/` から移行する場合は `frontend/scripts/migrate-backup-to-r2.mjs` を参照（一度きりの移行スクリプト、再実行しても既アップロード分はスキップされる）。

### 3. DBの準備（初回のみ・手動）

このリポジトリはDBコンテナを持たないため、`PG_HOST` が指す既存Postgresに対して事前に以下を用意しておく必要がある。

1. `PG_USER` / `PG_PASSWORD` のユーザー作成
2. `PG_DB` のデータベース作成
3. `pgvector` 拡張が使えること（`CREATE EXTENSION vector` が通ること）
4. `db/init/01_schema.sql`・`db/init/02_api_usage.sql`・`db/init/03_media_reveal.sql`・`db/init/04_share_links.sql` を対象DBに流し込んでテーブルを作成

```
psql "postgres://<PG_USER>:<PG_PASSWORD>@<PG_HOST>:<PG_PORT>/<PG_DB>" -f db/init/01_schema.sql
psql "postgres://<PG_USER>:<PG_PASSWORD>@<PG_HOST>:<PG_PORT>/<PG_DB>" -f db/init/02_api_usage.sql
psql "postgres://<PG_USER>:<PG_PASSWORD>@<PG_HOST>:<PG_PORT>/<PG_DB>" -f db/init/03_media_reveal.sql
psql "postgres://<PG_USER>:<PG_PASSWORD>@<PG_HOST>:<PG_PORT>/<PG_DB>" -f db/init/04_share_links.sql
```

アプリ側にマイグレーション処理は無いため、このSQLを流し忘れるとテーブル未作成・カラム未作成でエラーになる。
既存環境をアップデートする場合は、まだ流していない番号のファイルだけ追加で流せばよい（`01_schema.sql`は再実行しても`IF NOT EXISTS`のため無害）。

### 4. 起動

```
docker compose up -d --build
```
