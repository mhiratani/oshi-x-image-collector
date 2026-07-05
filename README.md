# oshi-x-image-collector

X（Twitter）の指定アカウントから画像を定期収集し、バックアップ・顔検出まで行うアプリ。

## 構成

- `frontend/` : Next.js アプリ（画面 + cronバッチワーカーを同一プロセスで実行）
- `frontend/lib/firestore.ts` : Firestore Admin SDK クライアント
- `frontend/lib/repo/` : Firestoreへのデータアクセス層（テーブル/コレクションごとの読み書き関数群）
- `docs/web-firestore-migration-design.md` : Postgres → Firestore 移行の設計（コレクション構成・インデックス等）
- `firestore.indexes.json` : Firestoreの複合インデックス定義（`firebase deploy --only firestore:indexes` でデプロイ）
- `android-app/` : Android版（Kotlin, 新規）。設計は `docs/android-app-design.md` を参照。現状はビルド可能な最小スケルトンのみ（機能未実装）

Web版のデータストアは **Firestore**（旧: 自前ホストのPostgres）。Web版・Android版は同じFirebaseプロジェクトを
使うことはできるが、コレクション構成が異なるためデータそのものは共有しない（詳細は上記設計書を参照）。

画像バックアップの実体は **Cloudflare R2**（S3互換オブジェクトストレージ）。
- `frontend/lib/r2.ts` : R2用のS3クライアント（`@aws-sdk/client-s3`）
- `frontend/worker/backup.js` : X CDNから取得した画像・サムネイルをR2へアップロード
- `frontend/app/backups/[...path]/route.ts` : `/backups/<x_user_id>/<media_key>.<ext>` へのリクエストをR2から都度取得して配信するプロキシ（R2の認証情報はサーバー側のみで保持し、バケット自体は非公開のままでよい）

## 初回セットアップ

### 1. .env を作成

```
cp .env.example .env
```

`.env` の各値（`X_BEARER_TOKEN`, `FIREBASE_*`, `AUTH_*`, `OIDC_*`, `CLOUDFLARE_*`）を埋める。

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

### 3. Firestoreの準備（初回のみ・手動）

このリポジトリはDBコンテナを持たず、Firebase Admin SDK経由でFirestoreに接続する構成。

1. [Firebaseコンソール](https://console.firebase.google.com/)でプロジェクトを新規作成（Firestoreを有効化しておく）
2. プロジェクトの設定 → サービスアカウント → 「新しい秘密鍵の生成」でJSONをダウンロード
3. JSON内の`project_id`/`client_email`/`private_key`を`.env`の`FIREBASE_PROJECT_ID`/`FIREBASE_CLIENT_EMAIL`/`FIREBASE_PRIVATE_KEY`に転記する
   （`private_key`は改行を`\n`にエスケープした1行文字列として保存する）
4. 複合インデックスを作成する（`firestore.indexes.json`にまとめてある）

```
npm install -g firebase-tools
firebase login
firebase deploy --only firestore:indexes --project <FIREBASE_PROJECT_ID>
```

コレクション構成・インデックス一覧の詳細は `docs/web-firestore-migration-design.md` を参照。

既存のPostgres環境からデータを移す場合は `frontend/scripts/migrate-postgres-to-firestore.mjs` を使う
（一度きりの移行スクリプト。`.env`に旧`PG_*`と新`FIREBASE_*`の両方を設定した状態で実行する。
再実行しても主キーをそのままドキュメントIDに使うため上書きになるだけで安全）。

```
docker build --target builder -t oshi-migrate ./frontend
docker run --rm --env-file .env oshi-migrate node scripts/migrate-postgres-to-firestore.mjs
```

### 4. 起動

```
docker compose up -d --build
```
