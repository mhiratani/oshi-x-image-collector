#!/bin/bash
# build_apk.sh - 配布用リリースAPK/AABのビルドスクリプト（署名付き）
#
# 使い方:
#   ./build_apk.sh           # APKのみビルド（デフォルト）
#   ./build_apk.sh --aab     # AABのみビルド（Play Store推奨）
#   ./build_apk.sh --both    # APKとAABの両方をビルド
#
# 出力先:
#   dist/oshi-x-image-collector-release-YYYYMMDD.apk  （日付付きAPKファイル）
#   dist/oshi-x-image-collector-release-latest.apk    （最新APKの上書きコピー）
#   dist/oshi-x-image-collector-release-YYYYMMDD.aab  （日付付きAABファイル）
#   dist/oshi-x-image-collector-release-latest.aab    （最新AABの上書きコピー）
#
# Play アプリ署名（Play App Signing）用:
#   dist/upload-key-certificate.pem  （Play Consoleへアップロード用の公開鍵証明書）
#
# keystoreファイルと鍵パスワードは dist/ に自動生成・保存されます。
# dist/ は .gitignore に含まれているためGitには含まれません。

set -e

# ────────────────────────────────────────────────
# 引数解析
# ────────────────────────────────────────────────

BUILD_APK=true
BUILD_AAB=false

for arg in "$@"; do
    case "$arg" in
        --aab)
            BUILD_APK=false
            BUILD_AAB=true
            ;;
        --both)
            BUILD_APK=true
            BUILD_AAB=true
            ;;
    esac
done

# ────────────────────────────────────────────────
# パス設定
# ────────────────────────────────────────────────

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$SCRIPT_DIR"
DIST_DIR="$SCRIPT_DIR/dist"
DATE_TAG="$(date +%Y%m%d)"

# APK ビルド成果物
APK_SRC="$PROJECT_DIR/app/build/outputs/apk/release/app-release-unsigned.apk"
APK_ALIGNED="$DIST_DIR/oshi-x-image-collector-release-aligned.apk"
APK_DATED="$DIST_DIR/oshi-x-image-collector-release-${DATE_TAG}.apk"
APK_LATEST="$DIST_DIR/oshi-x-image-collector-release-latest.apk"

# AAB ビルド成果物
AAB_SRC="$PROJECT_DIR/app/build/outputs/bundle/release/app-release.aab"
AAB_DATED="$DIST_DIR/oshi-x-image-collector-release-${DATE_TAG}.aab"
AAB_LATEST="$DIST_DIR/oshi-x-image-collector-release-latest.aab"

# keystore設定
KEYSTORE_PATH="$DIST_DIR/oshi-x-image-collector-release.keystore"
KEYSTORE_PASS_FILE="$DIST_DIR/keystore.pass"
KEY_ALIAS="oshi-x-image-collector-key"

# keystore 識別情報（DN: Distinguished Name）
# CN: 名前 / OU: 部署名 / O: 組織名 / L: 市区町村 / S: 都道府県 / C: 国コード（2文字）
KEY_CN="MasayukiHiratani"
KEY_OU="Kaihatsu-BU"
KEY_O="hilamalu.com"
KEY_L="Matsudo"
KEY_S="Chiba"
KEY_C="JP"

# Play アプリ署名用 公開鍵証明書
UPLOAD_KEY_CERT="$DIST_DIR/upload-key-certificate.pem"

# ────────────────────────────────────────────────
# Play アプリ署名（Play App Signing）設定
# ────────────────────────────────────────────────
#
# 【登録方法】
#   1. ./build_apk.sh --aab を実行してAABと公開鍵証明書を生成する
#   2. Play Console > アプリ > リリース > アプリ署名 を開く
#   3. 独自の署名鍵を使用する場合:
#      「アプリ署名鍵を変更」> 「秘密鍵と公開鍵証明書のアップロード」を選択し
#      upload-key-certificate.pem をアップロードする
#   4. 以降は dist/oshi-x-image-collector-release.keystore（アップロード鍵）でビルドするだけでOK
#
# 【鍵の役割】
#   アップロード鍵（Upload Key）  : 開発者が保持 → dist/oshi-x-image-collector-release.keystore
#   アプリ署名鍵（App Signing Key）: Google が管理・保護
#
# ────────────────────────────────────────────────

export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"

echo "================================================"
echo " oshi-x-image-collector APKビルドスクリプト（署名付きリリース）"
if $BUILD_AAB; then
    echo " モード: $( $BUILD_APK && echo 'APK + AAB' || echo 'AABのみ' )"
else
    echo " モード: APKのみ"
fi
echo "================================================"

# ────────────────────────────────────────────────
# 1. 事前チェック
# ────────────────────────────────────────────────

echo ""
echo "▶ [1/5] 事前チェック..."

# Java (JDK) 確認
if ! command -v java &> /dev/null; then
    echo "❌ Java が見つかりません。JDK 17 以上をインストールしてください。"
    echo "   sudo apt install openjdk-17-jdk-headless"
    exit 1
fi

JAVA_VER=$(java -version 2>&1 | head -1 | sed 's/.*version "\([0-9]*\).*/\1/')
if [ "$JAVA_VER" -lt 17 ] 2>/dev/null; then
    echo "❌ JDK 17 以上が必要です（現在: Java ${JAVA_VER}）"
    exit 1
fi
echo "   ✅ Java: $(java -version 2>&1 | head -1)"

# keytool 確認
if ! command -v keytool &> /dev/null; then
    echo "❌ keytool が見つかりません。JDK が正しくインストールされているか確認してください。"
    exit 1
fi
echo "   ✅ keytool: $(which keytool)"

# ANDROID_HOME 確認
if [ ! -d "$ANDROID_HOME" ]; then
    echo "❌ Android SDK が見つかりません: $ANDROID_HOME"
    echo "   ANDROID_HOME 環境変数を設定するか、~/android-sdk に SDK をインストールしてください。"
    exit 1
fi
echo "   ✅ ANDROID_HOME: $ANDROID_HOME"

# APK ビルド時のみ apksigner / zipalign が必要
if $BUILD_APK; then
    # apksigner の確認（.bat / 実行ファイル優先、.jar の場合は java -jar で実行）
    APKSIGNER_BIN=$(find "$ANDROID_HOME/build-tools" -name "apksigner.bat" -o -name "apksigner" ! -name "*.jar" 2>/dev/null | sort -V | tail -1)
    APKSIGNER_JAR=""
    if [ -z "$APKSIGNER_BIN" ]; then
        APKSIGNER_JAR=$(find "$ANDROID_HOME/build-tools" -name "apksigner.jar" 2>/dev/null | sort -V | tail -1)
        if [ -z "$APKSIGNER_JAR" ]; then
            echo "❌ apksigner が見つかりません。Android SDK build-tools をインストールしてください。"
            exit 1
        fi
        echo "   ✅ apksigner: java -jar $APKSIGNER_JAR"
    else
        echo "   ✅ apksigner: $APKSIGNER_BIN"
    fi

    # zipalign の確認
    ZIPALIGN_BIN=$(find "$ANDROID_HOME/build-tools" -name "zipalign*" 2>/dev/null | sort -V | tail -1)
    if [ -z "$ZIPALIGN_BIN" ]; then
        echo "❌ zipalign が見つかりません。Android SDK build-tools をインストールしてください。"
        exit 1
    fi
    echo "   ✅ zipalign: $ZIPALIGN_BIN"
fi

# apksigner 実行用関数
run_apksigner() {
    if [ -n "$APKSIGNER_BIN" ]; then
        "$APKSIGNER_BIN" "$@"
    else
        java -jar "$APKSIGNER_JAR" "$@"
    fi
}

# local.properties 確認・自動生成
LOCAL_PROPS="$PROJECT_DIR/local.properties"
if [ ! -f "$LOCAL_PROPS" ]; then
    echo "   ⚠️  local.properties が存在しないため自動生成します..."
    echo "sdk.dir=$ANDROID_HOME" > "$LOCAL_PROPS"
    echo "   ✅ local.properties 生成: sdk.dir=$ANDROID_HOME"
else
    echo "   ✅ local.properties: 存在確認済み"
fi

# gradlew の実行権限確認
if [ ! -x "$PROJECT_DIR/gradlew" ]; then
    echo "   ⚠️  gradlew に実行権限を付与します..."
    chmod +x "$PROJECT_DIR/gradlew"
fi

# dist/ ディレクトリ作成
mkdir -p "$DIST_DIR"

# ────────────────────────────────────────────────
# 2. keystore の確認・自動生成
# ────────────────────────────────────────────────

echo ""
echo "▶ [2/5] keystoreの確認・準備..."

# パスワードファイルの確認・自動生成（ランダム64文字）
if [ ! -f "$KEYSTORE_PASS_FILE" ]; then
    echo "   🔑 keystoreパスワードを新規生成します..."
    openssl rand -hex 32 | tr -d '\n' | head -c 64 > "$KEYSTORE_PASS_FILE"
    chmod 600 "$KEYSTORE_PASS_FILE"
    echo "   ✅ パスワードファイル生成: $KEYSTORE_PASS_FILE (パーミッション: 600)"
else
    echo "   ✅ パスワードファイル: 存在確認済み"
fi

KEYSTORE_PASS=$(cat "$KEYSTORE_PASS_FILE")
KEY_PASS="$KEYSTORE_PASS"

# keystoreの確認・自動生成
if [ ! -f "$KEYSTORE_PATH" ]; then
    echo "   🔑 keystoreを新規生成します..."
    keytool -genkeypair \
        -v \
        -keystore "$KEYSTORE_PATH" \
        -alias "$KEY_ALIAS" \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000 \
        -storepass "$KEYSTORE_PASS" \
        -keypass "$KEY_PASS" \
        -dname "CN=${KEY_CN}, OU=${KEY_OU}, O=${KEY_O}, L=${KEY_L}, S=${KEY_S}, C=${KEY_C}" \
        2>&1 | grep -v "^Warning:" || true
    chmod 600 "$KEYSTORE_PATH"
    echo "   ✅ keystore生成完了: $KEYSTORE_PATH"
    echo "   ⚠️  このkeystoreは同じアプリの更新インストールに必要です。バックアップしてください！"
else
    echo "   ✅ keystore: 存在確認済み ($KEYSTORE_PATH)"
fi

# ────────────────────────────────────────────────
# Play アプリ署名用: 公開鍵証明書のエクスポート
# ────────────────────────────────────────────────

echo ""
echo "   📄 Play アプリ署名用 公開鍵証明書をエクスポートします..."
keytool -export \
    -rfc \
    -keystore "$KEYSTORE_PATH" \
    -alias "$KEY_ALIAS" \
    -storepass "$KEYSTORE_PASS" \
    -file "$UPLOAD_KEY_CERT" \
    2>/dev/null
echo "   ✅ 公開鍵証明書エクスポート完了: $UPLOAD_KEY_CERT"
echo "   ℹ️  Play Console での独自署名鍵登録時、この .pem ファイルをアップロードしてください。"
echo "      Play Console > アプリ署名 > アプリ署名鍵を変更 > 秘密鍵と公開鍵証明書のアップロード"

# ────────────────────────────────────────────────
# 3. ビルド
# ────────────────────────────────────────────────

echo ""
echo "▶ [3/5] ビルド中..."
echo "   (初回はGradleやSDKのダウンロードが発生するため時間がかかります)"
echo ""

cd "$PROJECT_DIR"

if $BUILD_APK && $BUILD_AAB; then
    echo "   📦 APK + AAB の両方をビルドします..."
    ./gradlew assembleRelease bundleRelease --no-daemon
elif $BUILD_APK; then
    echo "   📦 APK をビルドします..."
    ./gradlew assembleRelease --no-daemon
elif $BUILD_AAB; then
    echo "   📦 AAB をビルドします..."
    ./gradlew bundleRelease --no-daemon
fi

echo ""
echo "   ✅ ビルド完了"

# ────────────────────────────────────────────────
# 4. APK: zipalign + apksigner で署名
# ────────────────────────────────────────────────

if $BUILD_APK; then
    echo ""
    echo "▶ [4/5] APK: zipalign + 署名処理..."

    # APK 存在確認
    if [ ! -f "$APK_SRC" ]; then
        echo "❌ APK が生成されていません: $APK_SRC"
        exit 1
    fi

    # zipalign（APKの最適化）
    echo "   📐 zipalign 実行中..."
    rm -f "$APK_ALIGNED"
    "$ZIPALIGN_BIN" -v 4 "$APK_SRC" "$APK_ALIGNED" > /dev/null
    echo "   ✅ zipalign 完了"

    # apksigner で署名
    echo "   🔏 apksigner で署名中..."
    run_apksigner sign \
        --ks "$KEYSTORE_PATH" \
        --ks-key-alias "$KEY_ALIAS" \
        --ks-pass "pass:${KEYSTORE_PASS}" \
        --key-pass "pass:${KEY_PASS}" \
        --out "$APK_DATED" \
        "$APK_ALIGNED"

    echo "   ✅ 署名完了"

    # 署名検証
    echo "   🔍 署名検証中..."
    run_apksigner verify "$APK_DATED" && echo "   ✅ 署名検証 OK"

    # 一時ファイル削除
    rm -f "$APK_ALIGNED"

    # latest コピー
    cp "$APK_DATED" "$APK_LATEST"
    echo "   ✅ APKコピー完了:"
    echo "      日付付き: $APK_DATED"
    echo "      最新版:   $APK_LATEST"
else
    echo ""
    echo "▶ [4/5] APKビルドはスキップ（--aab モード）"
fi

# ────────────────────────────────────────────────
# AAB: dist/ へコピー（Play Store は AAB をそのままアップロード）
# ────────────────────────────────────────────────

if $BUILD_AAB; then
    echo ""
    echo "   📦 AAB を dist/ へコピー中..."

    # AAB 存在確認
    if [ ! -f "$AAB_SRC" ]; then
        echo "❌ AAB が生成されていません: $AAB_SRC"
        exit 1
    fi

    cp "$AAB_SRC" "$AAB_DATED"
    cp "$AAB_SRC" "$AAB_LATEST"
    echo "   ✅ AABコピー完了:"
    echo "      日付付き: $AAB_DATED"
    echo "      最新版:   $AAB_LATEST"
    echo "   ℹ️  Play Console にアップロードする場合は AAB を使用してください（APKより推奨）"
fi

# ────────────────────────────────────────────────
# 5. dist/ フォルダへコピー＆情報表示
# ────────────────────────────────────────────────

echo ""
echo "▶ [5/5] ビルド情報..."

if $BUILD_APK; then
    APK_SIZE=$(du -h "$APK_LATEST" | cut -f1)
    echo ""
    echo "   [APK]"
    echo "   ファイルサイズ: $APK_SIZE"

    # SHA256 ハッシュ
    if command -v sha256sum &> /dev/null; then
        SHA256=$(sha256sum "$APK_LATEST" | awk '{print $1}')
        echo "   SHA256: $SHA256"
    elif command -v shasum &> /dev/null; then
        SHA256=$(shasum -a 256 "$APK_LATEST" | awk '{print $1}')
        echo "   SHA256: $SHA256"
    fi

    # 署名情報
    echo ""
    echo "   署名情報:"
    run_apksigner verify --print-certs "$APK_LATEST" 2>/dev/null \
        | grep -E "Signer|DN:|SHA" \
        | sed 's/^/      /' \
        || echo "      (署名情報の取得に失敗しました)"
fi

if $BUILD_AAB; then
    AAB_SIZE=$(du -h "$AAB_LATEST" | cut -f1)
    echo ""
    echo "   [AAB]"
    echo "   ファイルサイズ: $AAB_SIZE"

    # SHA256 ハッシュ
    if command -v sha256sum &> /dev/null; then
        SHA256_AAB=$(sha256sum "$AAB_LATEST" | awk '{print $1}')
        echo "   SHA256: $SHA256_AAB"
    elif command -v shasum &> /dev/null; then
        SHA256_AAB=$(shasum -a 256 "$AAB_LATEST" | awk '{print $1}')
        echo "   SHA256: $SHA256_AAB"
    fi
fi

# ────────────────────────────────────────────────
# 完了メッセージ
# ────────────────────────────────────────────────

echo ""
echo "================================================"
echo " ✅ ビルド完了（署名付きリリース）"
echo "================================================"
echo ""

if $BUILD_APK; then
    echo " 配布用APK:"
    echo "   $APK_LATEST"
    echo ""
fi

if $BUILD_AAB; then
    echo " Play Store用AAB:"
    echo "   $AAB_LATEST"
    echo ""
fi

echo " keystore（バックアップ推奨）:"
echo "   $KEYSTORE_PATH"
echo "   $KEYSTORE_PASS_FILE"
echo ""
echo " Play アプリ署名用 公開鍵証明書:"
echo "   $UPLOAD_KEY_CERT"
echo "   ↑ Play Console > アプリ署名 > 独自の署名鍵を使用する場合にアップロード"
echo ""

if $BUILD_APK; then
    echo " adbでインストールする場合:"
    echo "   adb install $APK_LATEST"
    echo "   adb install -r $APK_LATEST   # 上書きインストール"
    echo ""
fi
