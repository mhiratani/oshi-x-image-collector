# oshi-x-image-collector

X（Twitter）の指定アカウントから画像を定期収集し、バックアップ・顔検出まで行うアプリ。

## 構成

- `frontend/` : Next.js アプリ（画面 + cronバッチワーカーを同一プロセスで実行）
- `frontend/lib/firestore.ts` : Firestore Admin SDK クライアント
- `frontend/lib/repo/` : Firestoreへのデータアクセス層（テーブル/コレクションごとの読み書き関数群）
- `docs/web-firestore-migration-design.md` : Postgres → Firestore 移行の設計（コレクション構成・インデックス等）
- `docs/web-firestore-migration-runbook.md` : 本番環境での移行・切り替え手順（メンテナンス手順・検証チェックリスト・ロールバック手順）
- `firestore.indexes.json` : Firestoreの複合インデックス定義（`firebase deploy --only firestore:indexes` でデプロイ）
- `android-app/` : Android版（Kotlin, 新規）。設計は `docs/android-app-design.md` を参照。現状はビルド可能な最小スケルトンのみ（機能未実装）

Web版のデータストアは **Firestore**。Web版・Android版は同じFirebaseプロジェクト・
同じuidのFirestoreツリー(`users/{uid}/...`)を共有しており、同じGoogleアカウントであれば
どちらからログインしても同じデータが見える（詳細は `docs/web-android-user-tree-unification-design.md` を参照）。

画像バックアップの実体は **Cloudflare R2**（S3互換オブジェクトストレージ）。
- `frontend/lib/r2.ts` : R2用のS3クライアント（`@aws-sdk/client-s3`）
- `frontend/worker/backup.js` : X CDNから取得した画像・サムネイルをR2へアップロード
- `frontend/app/backups/[...path]/route.ts` : `/backups/<x_user_id>/<media_key>.<ext>` へのリクエストをR2から都度取得して配信するプロキシ（R2の認証情報はサーバー側のみで保持し、バケット自体は非公開のままでよい）

## 認証

Web版はFirebase Auth（Google Sign-Inのみ）でログインする。ただし任意のGoogleアカウントを
受け入れているわけではなく、`.env`の`OWNER_UID`（本人のFirebase uid）と一致するアカウントだけに
セッションを発行する「一人用のホワイトリスト方式」（`frontend/auth.ts`の`signIn`コールバック）。

- 他人が誤って/意図的にログインを試みても、Google認証自体は成功する（Firebaseコンソールの
  Authentication → Usersには登録される）が、`OWNER_UID`と一致しないためNextAuthのセッションは
  発行されず、全ページ・全APIが`/login`リダイレクト/401で弾く（`frontend/middleware.ts`）。
- Android版アプリで先にGoogle Sign-Inしてuidを確認し、その値を`OWNER_UID`に設定する運用
  （詳細は `docs/web-android-user-tree-unification-design.md` の実施順序を参照）。

## 初回セットアップ

### 1. .env を作成

```
cp .env.example .env
```

`.env` の各値（`X_BEARER_TOKEN`, `FIREBASE_*`, `AUTH_*`, `NEXT_PUBLIC_FIREBASE_*`, `OWNER_UID`, `CLOUDFLARE_*`）を埋める。

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

### 4. いいね・リポスト送信の設定（任意）

拡大表示の「🤍 Xでいいね」「🔁 Xでリポスト」ボタンで、画像の元ツイートへ自分のアカウントとして
いいね・リポストを送れる。`X_BEARER_TOKEN`（アプリ認証・読み取り専用）では書き込みできないため、
別途OAuth 1.0aのユーザー認証情報が必要（`frontend/lib/xWrite.ts`）。

1. [開発者ポータル](https://developer.x.com/)のApp → **Settings** → User authentication settings で
   App permissions を **Read and write** にする
2. **Keys and tokens** で API Key and Secret を確認し、Access Token and Secret を(再)生成する
   （権限変更前に発行したトークンはRead onlyのままなので再生成が必要）
3. `.env` の `X_API_KEY` / `X_API_SECRET` / `X_ACCESS_TOKEN` / `X_ACCESS_TOKEN_SECRET` に転記する

未設定でも収集・閲覧には影響しない（送信ボタンを押したときにエラーメッセージが出るだけ）。
いいねはトグル操作のため、送信済みの画像（Firestoreの`liked_on_x`）には再送しない。
リポストは投げっぱなし（X側で解除した後の再リポストを許すため、送信済みでも再送できる）。
手元の🌟お気に入り（`is_favorite`）とは独立して管理される。

### 5. 起動

```
docker compose up -d --build
```
