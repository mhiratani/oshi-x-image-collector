package com.hilamalu.oshixcollector.data.settings

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** X Bearer Token / R2認証情報 / Firebaseプロジェクト設定をEncryptedSharedPreferencesに保存する。 */
class SecureSettings(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "secure_settings",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var xBearerToken: String?
        get() = prefs.getString(KEY_X_BEARER_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_X_BEARER_TOKEN, value).apply()

    var r2BucketName: String?
        get() = prefs.getString(KEY_R2_BUCKET_NAME, null)
        set(value) = prefs.edit().putString(KEY_R2_BUCKET_NAME, value).apply()

    var r2AccountId: String?
        get() = prefs.getString(KEY_R2_ACCOUNT_ID, null)
        set(value) = prefs.edit().putString(KEY_R2_ACCOUNT_ID, value).apply()

    var r2AccessKeyId: String?
        get() = prefs.getString(KEY_R2_ACCESS_KEY_ID, null)
        set(value) = prefs.edit().putString(KEY_R2_ACCESS_KEY_ID, value).apply()

    var r2SecretAccessKey: String?
        get() = prefs.getString(KEY_R2_SECRET_ACCESS_KEY, null)
        set(value) = prefs.edit().putString(KEY_R2_SECRET_ACCESS_KEY, value).apply()

    /** `https://<ACCOUNT_ID>.r2.cloudflarestorage.com` 形式。 */
    var r2Endpoint: String?
        get() = prefs.getString(KEY_R2_ENDPOINT, null)
        set(value) = prefs.edit().putString(KEY_R2_ENDPOINT, value).apply()

    val isR2Configured: Boolean
        get() = !r2BucketName.isNullOrBlank() && !r2AccessKeyId.isNullOrBlank() &&
            !r2SecretAccessKey.isNullOrBlank() && !r2Endpoint.isNullOrBlank()

    // ── Firebase（Firestoreクラウドバックアップ・Google Sign-In用） ──
    // Firebaseコンソール「プロジェクトの設定」から取得できる値。APIキーを含め
    // いずれも非秘匿情報（Firestoreの安全性はセキュリティルール側で担保するため、
    // 値そのものを隠す必要はない）。google-services.json は使わず、この4項目から
    // 実行時にFirebaseOptionsで初期化する。

    /** Firebaseコンソール > プロジェクトの設定 > 全般 > ウェブAPIキー */
    var firebaseApiKey: String?
        get() = prefs.getString(KEY_FIREBASE_API_KEY, null)
        set(value) = prefs.edit().putString(KEY_FIREBASE_API_KEY, value).apply()

    /** Firebaseコンソール > プロジェクトの設定 > 全般 > プロジェクトID */
    var firebaseProjectId: String?
        get() = prefs.getString(KEY_FIREBASE_PROJECT_ID, null)
        set(value) = prefs.edit().putString(KEY_FIREBASE_PROJECT_ID, value).apply()

    /** Firebaseコンソール > プロジェクトの設定 > 全般 > Androidアプリ > アプリID（`1:xxx:android:xxx`） */
    var firebaseAppId: String?
        get() = prefs.getString(KEY_FIREBASE_APP_ID, null)
        set(value) = prefs.edit().putString(KEY_FIREBASE_APP_ID, value).apply()

    /** Firebaseコンソール > Authentication > Sign-in method > Google > ウェブSDK構成 > ウェブクライアントID */
    var firebaseWebClientId: String?
        get() = prefs.getString(KEY_FIREBASE_WEB_CLIENT_ID, null)
        set(value) = prefs.edit().putString(KEY_FIREBASE_WEB_CLIENT_ID, value).apply()

    val isFirebaseConfigured: Boolean
        get() = !firebaseApiKey.isNullOrBlank() && !firebaseProjectId.isNullOrBlank() &&
            !firebaseAppId.isNullOrBlank() && !firebaseWebClientId.isNullOrBlank()

    private companion object {
        const val KEY_X_BEARER_TOKEN = "x_bearer_token"
        const val KEY_R2_BUCKET_NAME = "r2_bucket_name"
        const val KEY_R2_ACCOUNT_ID = "r2_account_id"
        const val KEY_R2_ACCESS_KEY_ID = "r2_access_key_id"
        const val KEY_R2_SECRET_ACCESS_KEY = "r2_secret_access_key"
        const val KEY_R2_ENDPOINT = "r2_endpoint"
        const val KEY_FIREBASE_API_KEY = "firebase_api_key"
        const val KEY_FIREBASE_PROJECT_ID = "firebase_project_id"
        const val KEY_FIREBASE_APP_ID = "firebase_app_id"
        const val KEY_FIREBASE_WEB_CLIENT_ID = "firebase_web_client_id"
    }
}
