package com.hilamalu.oshixcollector.data.backup

import android.content.Context
import com.google.firebase.FirebaseApp

/**
 * `google-services.json` が未配置の状態でもアプリ全体のビルド・ローカル機能が動くように、
 * Firebase関連コードを呼ぶ前に必ずこれで初期化有無を確認する。
 * （未配置時は [com.google.firebase.auth.FirebaseAuth.getInstance] 等が例外を投げるため）
 */
object FirebaseAvailability {
    fun isConfigured(context: Context): Boolean = FirebaseApp.getApps(context).isNotEmpty()
}
