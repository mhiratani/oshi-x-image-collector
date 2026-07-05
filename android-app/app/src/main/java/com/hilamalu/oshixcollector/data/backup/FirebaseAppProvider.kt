package com.hilamalu.oshixcollector.data.backup

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.hilamalu.oshixcollector.data.settings.SecureSettings

/**
 * `google-services.json` を使わず、設定画面で入力したFirebaseプロジェクトの値
 * （[SecureSettings.firebaseApiKey]等）から実行時に名前付きFirebaseAppを初期化する。
 * 複数のFirebaseプロジェクトを1アプリに同居させる際の標準的な方法（named app）。
 */
object FirebaseAppProvider {
    private const val APP_NAME = "oshiBackup"

    /** 未設定（項目が空）の場合は null。 */
    fun getOrNull(context: Context, secureSettings: SecureSettings): FirebaseApp? {
        if (!secureSettings.isFirebaseConfigured) return null

        FirebaseApp.getApps(context).firstOrNull { it.name == APP_NAME }?.let { return it }

        val options = FirebaseOptions.Builder()
            .setApiKey(secureSettings.firebaseApiKey!!)
            .setProjectId(secureSettings.firebaseProjectId!!)
            .setApplicationId(secureSettings.firebaseAppId!!)
            .build()
        return FirebaseApp.initializeApp(context, options, APP_NAME)
    }

    /**
     * 設定画面でFirebase情報を変更した直後に呼ぶ。既存の初期化済みAppは古い値を
     * 保持し続けるため、削除して次回 [getOrNull] で新しい値から再初期化させる。
     */
    fun reset(context: Context) {
        FirebaseApp.getApps(context).firstOrNull { it.name == APP_NAME }?.delete()
    }
}
