import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// ──────────────────────────────────────────────────────────────
// 署名設定を app/local.properties から読み込む（git 管理外）
//   KEYSTORE_PATH      : keystoreファイルのパス（app/ からの相対パス or 絶対パス）
//   KEY_ALIAS          : キーのエイリアス
//   KEYSTORE_PASSWORD  : keystoreのパスワード
//   KEY_PASSWORD       : キーのパスワード
// ──────────────────────────────────────────────────────────────
val appLocalProps = Properties().also { props ->
    val f = file("local.properties")
    if (f.exists()) f.inputStream().use { props.load(it) }
}

// ──────────────────────────────────────────────────────────────
// Firebase (google-services) プラグインは google-services.json が
// 配置されている場合のみ適用する。未配置でもローカル機能のビルド・実行が
// 通るようにするため（クラウドバックアップ関連コードは実行時に
// FirebaseApp 初期化有無をチェックしてガードする）。
// セットアップ手順: android-app/README.md 参照。
// ──────────────────────────────────────────────────────────────
val hasGoogleServicesConfig = file("google-services.json").exists()
if (hasGoogleServicesConfig) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.hilamalu.oshixcollector"
    compileSdk = 36

    signingConfigs {
        create("release") {
            val ksPath = appLocalProps.getProperty("KEYSTORE_PATH", "")
            storeFile = if (ksPath.isNotEmpty()) file(ksPath) else null
            keyAlias = appLocalProps.getProperty("KEY_ALIAS", "")
            storePassword = appLocalProps.getProperty("KEYSTORE_PASSWORD", "")
            keyPassword = appLocalProps.getProperty("KEY_PASSWORD", "")
        }
    }

    defaultConfig {
        applicationId = "com.hilamalu.oshixcollector"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isDebuggable = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }

    // Allow plain JVM unit tests to reference Android framework constants
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// Export Room schema JSON to app/schemas so schema changes are tracked in Git.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Workaround for AGP 9.x: Android Studio invokes compile-only meta tasks
// (:app:testClasses / :app:unitTestClasses / :app:androidTestClasses) which no
// longer exist in AGP 9.x. Register no-op placeholders so syncs/builds from the
// IDE don't fail with "task 'xxx' not found in project ':app'".
listOf("testClasses", "unitTestClasses", "androidTestClasses").forEach { name ->
    if (tasks.findByName(name) == null) {
        tasks.register(name)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // Room（ローカルメタデータ保存）
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Coil（画像表示）
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // X APIクライアント / R2アップロード
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    // 認証情報・設定の保存
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.datastore.preferences)

    // Firebase（クラウドバックアップ: Firestore + Auth）
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth.ktx)
    implementation(libs.firebase.firestore.ktx)

    // Google Sign-In（Credential Manager経由）
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
