# oshi-x-image-collector (Android)

X（Twitter）の指定アカウントの画像をAndroid端末単体で収集・閲覧するアプリ。設計は [`docs/android-app-design.md`](../docs/android-app-design.md) を参照。

X API取得→ローカル保存（Room）→一覧表示、およびクラウドバックアップ（Firebase Firestore + Cloudflare R2）まで実装済み。

## 構成

Gradle構成・`build_apk.sh` の作りは同リポジトリ管理者の [oshi-wall](https://github.com/mhiratani/oshi-wall) を参考にしている。

- `app/` : アプリ本体（Kotlin, Jetpack Compose）
  - `namespace` / `applicationId`: `com.hilamalu.oshixcollector`
  - `data/db/` : Roomによるローカルメタデータ（追跡アカウント・画像）
  - `data/xapi/` : X API v2クライアント（ユーザー自身のBearer Token）
  - `data/ImageStorage.kt` : 画像本体のローカル保存
  - `data/settings/SecureSettings.kt` : Bearer Token・R2認証情報の暗号化保存
  - `data/backup/` : クラウドバックアップ（Google Sign-In + Firestoreミラー + R2アップロード）
  - `ui/` : 画像一覧・アカウント管理・設定の3画面（Compose Navigation）
- `gradle/libs.versions.toml` : バージョンカタログ（AGP 9.1.1 / Kotlin 2.2.10 / Compose BOM 2024.09.00）
- `build_apk.sh` : 署名付きリリースAPK/AABのビルドスクリプト（keystoreは初回実行時に自動生成、`dist/` はgit管理外）
- `firestore.rules` : クラウドバックアップ用Firestoreセキュリティルール（Firebaseコンソールに貼り付ける）

## セットアップ

### 1. Android SDK

```
cp local.properties.example local.properties
# sdk.dir を実際のAndroid SDKパスに書き換える
```

### 2. クラウドバックアップを使う場合（任意機能）

ローカルでのX画像収集・閲覧だけならこの手順は不要（`google-services.json` が無くてもビルド・実行できる）。クラウドバックアップを使う場合のみ以下を行う。

1. [Firebase Console](https://console.firebase.google.com/) で新規プロジェクトを作成
2. Android アプリを追加（パッケージ名 `com.hilamalu.oshixcollector`）→ `google-services.json` をダウンロードし `android-app/app/google-services.json` に配置（git管理外）
3. Firestore Database を有効化
4. Authentication → Sign-in method で **Google** を有効化
5. デバッグ用SHA-1フィンガープリントをFirebaseコンソールのAndroidアプリ設定に登録（`./gradlew signingReport` で取得できる。Google Sign-Inに必須）
6. Firestore Database > ルール タブに、このリポジトリの `firestore.rules` の内容を貼り付けて公開する

これらが未完了の場合、設定画面で「Firebaseが未設定のため利用できません」と表示され、クラウドバックアップのトグルは機能しない（ローカル収集・閲覧には影響しない）。

### 3. アプリ内の設定

初回起動後、設定タブで以下を入力する。

- X Bearer Token（X Developer Portalで取得した自分のBearer Token）
- （クラウドバックアップを使う場合）Cloudflare R2のバケット名・アカウントID・アクセスキーID・シークレットアクセスキー・エンドポイント

## ビルド

```
./build_apk.sh           # 署名付きAPKをビルド（dist/ に出力）
./build_apk.sh --aab     # AAB（Play Store提出用）
./build_apk.sh --both    # 両方
```

Android Studioで `android-app/` を開いて通常通りデバッグ実行することも可能。

## 未実装（今後のセッションで対応）

- ML Kit Face Detection（design.md 3.4）
- WorkManagerによる定期同期（design.mdでは必須要件ではないとされている）
- Web側（Next.js）のPostgres→Firestore移行（design.md参照。今回はAndroid側を先行実装したため、Web側は当面Postgresのまま）

## 検証について

このリポジトリの開発環境ではAndroid SDKが使えずGoogle Mavenへのアクセスも制限されているため、実機/エミュレータでのビルド・実行・Firebase接続確認はできていない。Android Studioがある環境で一度ビルド・実機確認を行うことを推奨する。
