package com.hilamalu.oshixcollector.data.backup

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.tasks.await

/**
 * クラウドバックアップの書き込み先（Firestore）を一意なuidで区別するための
 * 軽量なGoogle Sign-In。design.md「Androidはユーザー管理を持たない」は
 * app_users/user_subscriptions相当のマルチテナント管理が不要という意味であり、
 * これはそれとは別（Firestoreセキュリティルールのための本人確認用）。
 */
class GoogleAuthManager(private val context: Context) {
    private val credentialManager by lazy { CredentialManager.create(context) }
    private val auth: FirebaseAuth by lazy { Firebase.auth }

    val currentUser: FirebaseUser?
        get() = if (FirebaseAvailability.isConfigured(context)) auth.currentUser else null

    /** google-services.json 未配置の場合は null（Google Sign-Inは利用不可）。 */
    private fun webClientId(): String? {
        val id = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        return if (id != 0) context.getString(id) else null
    }

    suspend fun signIn(): FirebaseUser {
        check(FirebaseAvailability.isConfigured(context)) {
            "Firebaseが未設定です（google-services.jsonを配置してください）"
        }
        val webClientId = webClientId()
            ?: error("default_web_client_id が見つかりません（google-services.jsonを配置してください）")

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
        if (FirebaseAvailability.isConfigured(context)) auth.signOut()
    }
}
