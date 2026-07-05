# oshi-x-image-collector (Android)

X（Twitter）の指定アカウントの画像をAndroid端末単体で収集・閲覧するアプリ。設計は [`docs/android-app-design.md`](../docs/android-app-design.md) を参照。

現状はビルド可能な最小スケルトン（空のMainActivityのみ）。機能は今後のセッションで段階的に実装する。

## 構成

Gradle構成・`build_apk.sh` の作りは同リポジトリ管理者の [oshi-wall](https://github.com/mhiratani/oshi-wall) を参考にしている。

- `app/` : アプリ本体（Kotlin, Jetpack Compose）
  - `namespace` / `applicationId`: `com.hilamalu.oshixcollector`
- `gradle/libs.versions.toml` : バージョンカタログ（AGP 9.1.1 / Kotlin 2.2.10 / Compose BOM 2024.09.00）
- `build_apk.sh` : 署名付きリリースAPK/AABのビルドスクリプト（keystoreは初回実行時に自動生成、`dist/` はgit管理外）

## セットアップ

```
cp local.properties.example local.properties
# sdk.dir を実際のAndroid SDKパスに書き換える
```

## ビルド

```
./build_apk.sh           # 署名付きAPKをビルド（dist/ に出力）
./build_apk.sh --aab     # AAB（Play Store提出用）
./build_apk.sh --both    # 両方
```

Android Studioで `android-app/` を開いて通常通りデバッグ実行することも可能。

## 未実装（今後のセッションで対応）

`docs/android-app-design.md` に記載の以下が未実装:

- X API クライアント（ユーザー自身のBearer Tokenで画像取得）
- Room によるローカルメタデータ管理
- ローカル画像ストレージ・一覧表示UI
- クラウドバックアップ（設定時のみ、Postgres/R2への片方向書き込み）
  - 素のPostgresソケット接続はAndroidで非対応のため、PostgREST/Supabase等HTTP経由の方式を別途検討する必要あり
- ML Kit Face Detection
