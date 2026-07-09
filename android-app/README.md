# oshi-x-image-collector (Android)

X（Twitter）の指定アカウントの画像をAndroid端末単体で収集・閲覧するアプリ。設計は [`docs/android-app-design.md`](../docs/android-app-design.md) を参照。

X API取得→ローカル保存（Room）→一覧表示、顔検出によるフィルター（ML Kit）、クラウドバックアップ（Firebase Firestore + Cloudflare R2）、およびクラウドバックアップからの復元まで実装済み。

## 構成

Gradle構成・`build_apk.sh` の作りは同リポジトリ管理者の [oshi-wall](https://github.com/mhiratani/oshi-wall) を参考にしている。

- `app/` : アプリ本体（Kotlin, Jetpack Compose）
  - `namespace` / `applicationId`: `com.hilamalu.oshixcollector`
  - `data/db/` : Roomによるローカルメタデータ（追跡アカウント・画像）
  - `data/xapi/` : X API v2クライアント（ユーザー自身のBearer Token）
  - `data/ImageStorage.kt` : 画像本体のローカル保存
  - `data/settings/SecureSettings.kt` : Bearer Token・R2認証情報の暗号化保存
  - `data/backup/` : クラウドバックアップ（Google Sign-In + Firestoreミラー + R2アップロード）
  - `data/face/FaceDetector.kt` : ML Kit Face Detectionによる顔判定（oshi-wallの`FocalPointDetector.kt`のパターンを踏襲）
  - `ui/` : 画像一覧・アカウント管理・設定の3画面（Compose Navigation）
- `gradle/libs.versions.toml` : バージョンカタログ（AGP 9.1.1 / Kotlin 2.2.10 / Compose BOM 2024.09.00）
- `build_apk.sh` : 署名付きリリースAPK/AABのビルドスクリプト（keystoreは初回実行時に自動生成、`dist/` はgit管理外）
- `firestore.rules` : クラウドバックアップ用Firestoreセキュリティルール（Firebaseコンソールに貼り付ける）

## GitHub ActionsでのAPK自動ビルド

mainブランチの `android-app/` 配下が更新されると、GitHub Actions（[`.github/workflows/android-apk.yml`](../.github/workflows/android-apk.yml)）が署名付きAPKをビルドし、Release **android-latest** に添付する。スマホのブラウザ/GitHubアプリでReleaseページを開けば、そのままAPKをダウンロード・インストールできる（手動実行はActionsタブのworkflow_dispatchから）。

初回セットアップ（1回だけ）: 手元PCの `android-app/dist/` にあるkeystoreをリポジトリのSecrets（Settings > Secrets and variables > Actions）へ登録する。**インストール済みAPKと同じ署名にするため必須**（別のkeystoreで署名すると上書きインストールできない）。

| Secret名 | 値 |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 dist/oshi-x-image-collector-release.keystore` の出力 |
| `ANDROID_KEYSTORE_PASSWORD` | `dist/keystore.pass` の中身 |

## セットアップ

### 1. Android SDK

```
cp local.properties.example local.properties
# sdk.dir を実際のAndroid SDKパスに書き換える
```

### 2. クラウドバックアップを使う場合（任意機能）

ローカルでのX画像収集・閲覧だけならこの手順は不要。クラウドバックアップは**アプリのビルドとは無関係**で、設定画面から値を入力するだけで有効化できる（`google-services.json` のようなビルド時ファイルは使わない）。

以下はFirebaseコンソール側での一度きりの準備（Googleアカウントでの操作が必要なため開発者自身が行う）。

1. [Firebase Console](https://console.firebase.google.com/) で新規プロジェクトを作成
2. Android アプリを追加（パッケージ名 `com.hilamalu.oshixcollector`）。`google-services.json` はダウンロードしても使わないので破棄してよいが、**SHA-1フィンガープリントはこの時点で登録しておく**（`./gradlew signingReport` で取得できる。Google Sign-Inの信頼済みアプリ判定に使われるため未登録だとサインインに失敗することがある）
3. Firestore Database を有効化
4. Authentication → Sign-in method で **Google** を有効化 → 表示される「ウェブSDK構成」の**ウェブクライアントID**を控える
5. Firestore Database > ルール タブに、このリポジトリの `firestore.rules` の内容を貼り付けて公開する
6. プロジェクトの設定（歯車アイコン）> 全般 タブで、**ウェブAPIキー**・**プロジェクトID**を控える。「マイアプリ」に追加したAndroidアプリの**アプリID**（`1:数字:android:16進数`の形式）も控える

これでコンソール側の準備は完了。あとはアプリの設定画面（下記3.）にこの4つの値（APIキー・プロジェクトID・アプリID・ウェブクライアントID）を入力するだけでよい。同じ値を友人など他の人の端末に入力してもらえば、同じFirebaseプロジェクトに（各自のGoogleアカウントで分離されて）バックアップされる。

未入力の場合、設定画面に「Firebase設定を保存すると、クラウドバックアップが利用できるようになります」と表示され、クラウドバックアップのトグルは機能しない（ローカル収集・閲覧には影響しない）。

### 3. アプリ内の設定

初回起動後、設定タブで以下を入力する。

- X Bearer Token（X Developer Portalで取得した自分のBearer Token）
- （クラウドバックアップを使う場合）
  - Cloudflare R2のバケット名・アカウントID・アクセスキーID・シークレットアクセスキー・エンドポイント
  - Firebaseの APIキー・プロジェクトID・アプリID・ウェブクライアントID（上記2.で控えた値）→ 保存後、トグルをONにするとGoogleサインイン画面が起動する

## ビルド

```
./build_apk.sh           # 署名付きAPKをビルド（dist/ に出力）
./build_apk.sh --aab     # AAB（Play Store提出用）
./build_apk.sh --both    # 両方
```

Android Studioで `android-app/` を開いて通常通りデバッグ実行することも可能。

## 顔フィルターについて

「最新を取得」時に、新規ダウンロード画像および前回判定できなかった画像に対してML Kit Face Detection（オンデバイス・無料）で顔の有無を判定する。画像一覧画面右上の「顔のみ」チップで、顔が写っている画像だけに絞り込める。

ML Kitのモデルは初回インストール時にPlay Services経由でバックグラウンド取得されるため、インストール直後は判定できないことがある（`isFace`はnullのまま残り、次回の「最新を取得」時に自動で再試行される）。

Web版のBlazeFaceと異なり、ML Kitは生の確率値を公開していないため、`faceConfidence`は検出有無から作った1.0/0.0の疑似値になる（Web版の値とは意味が異なる点に注意）。

## 初回起動オンボーディング

追跡アカウントが1件も無い状態（初回起動・再インストール直後など）でアプリを開くと、通常の3タブより先に案内画面が表示される。「はじめる」→「クラウドバックアップから復元しますか？」→（いいえの場合）「クラウドバックアップを利用しますか？」の順に選択させ、選択に応じてサインイン・復元・バックアップ有効化を行ってからアプリ本体（画像一覧タブ）へ進む。起動済みフラグは持たず、追跡アカウントが0件である限り毎回この画面を経由する。

質問には常にFirebase設定の有無に関わらず答えられる。「はい」を選んだ時点でFirebase未設定だった場合は、設定画面（Firebase/R2の入力欄がある）へ誘導される（初めて使うユーザーが機能の存在を知らずに素通りしてしまわないようにするため）。設定画面で必要な値を保存した後は、既存の「クラウドから復元」ボタンやクラウドバックアップのトグルから改めて実行できる。

## クラウドバックアップからの復元

機種変更・端末紛失・アプリ再インストール時に、クラウド（Firestore + R2）に保存済みのデータを端末へ戻すための機能。上記の初回起動オンボーディングから選ぶか、既存ユーザーは設定画面のクラウドバックアップ区画にある「クラウドから復元」ボタンから手動で実行できる。

- Firestoreからアカウント一覧・メディアメタデータを取得し、ローカルにまだ無い行だけをRoomへ追加する（**既存のローカル行は上書きしない**）。
- R2にバックアップ済み（`r2BackupUrl`が設定済み）だがローカルに画像ファイルが無いメディアについて、画像本体をR2からダウンロードして保存する。
- 途中で失敗しても、追加のみの設計のため再度「クラウドから復元」を押せば安全に再開できる。

既知の制約:
- 追加のみで、クラウド側が新しくてもローカルの既存行を上書きすることはない（別端末での変更をこの端末に同期する用途には使えない）。
- ローカルで削除したアカウント/メディアも、Firestore側に残っていれば復元で復活する（削除の伝播は未実装）。

## 未実装（今後のセッションで対応）

- WorkManagerによる定期同期（design.mdでは必須要件ではないとされている）

## 検証について

このリポジトリの開発環境ではAndroid SDKが使えずGoogle Mavenへのアクセスも制限されているため、実機/エミュレータでのビルド・実行・Firebase接続確認はできていない。Android Studioがある環境で一度ビルド・実機確認を行うことを推奨する。
