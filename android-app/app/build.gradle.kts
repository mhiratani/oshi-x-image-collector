import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
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

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
