package com.hilamalu.oshixcollector.data.backup

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.hilamalu.oshixcollector.data.settings.SecureSettings
import kotlinx.coroutines.tasks.await

/**
 * クラウドバックアップの書き込み先（Firestore）を一意なuidで区別するための
 * 軽量なGoogle Sign-In。design.md「Androidはユーザー管理を持たない」は
 * app_users/user_subscriptions相当のマルチテナント管理が不要という意味であり、
 * これはそれとは別（Firestoreセキュリティルールのための本人確認用）。
 *
 * Firebaseプロジェクトの設定は google-services.json ではなく設定画面からの
 * 入力値（[SecureSettings]）を使うため、[FirebaseAppProvider] 経由で名前付き
 * FirebaseAppを解決してから FirebaseAuth を取得する。
 */
class GoogleAuthManager(private val context: Context) {
    private val secureSettings = SecureSettings(context)
    private val credentialManager by lazy { CredentialManager.create(context) }

    private fun authOrNull(): FirebaseAuth? =
        FirebaseAppProvider.getOrNull(context, secureSettings)?.let { FirebaseAuth.getInstance(it) }

    val currentUser: FirebaseUser?
        get() = authOrNull()?.currentUser

    suspend fun signIn(): FirebaseUser {
        val auth = authOrNull()
            ?: error("Firebaseが未設定です（設定画面でAPIキー等を入力してください）")
        val webClientId = secureSettings.firebaseWebClientId
            ?: error("Google Sign-In用クライアントIDが未設定です")

        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(webClientId)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        val result = credentialManager.getCredential(context, request)
        val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(result.credential.data)
        val firebaseCredential = GoogleAuthProvider.getCredential(googleIdTokenCredential.idToken, null)
        val authResult = auth.signInWithCredential(firebaseCredential).await()
        return authResult.user ?: error("サインインに失敗しました")
    }

    fun signOut() {
        authOrNull()?.signOut()
    }
}
