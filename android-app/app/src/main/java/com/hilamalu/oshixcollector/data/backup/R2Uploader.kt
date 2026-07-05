package com.hilamalu.oshixcollector.data.backup

import com.hilamalu.oshixcollector.data.settings.SecureSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 画像をCloudflare R2へ直接PUTする（design.md 3.3の「クラウドバックアップON時のみ、
 * 設定画面で入力したR2/S3のクレデンシャルを使ってAndroidアプリから直接アップロード」）。
 * キー命名は [frontend/worker/backup.js] と同じ `<x_user_id>/<media_key>.<ext>` に揃える。
 */
class R2Uploader(
    private val secureSettings: SecureSettings,
    private val httpClient: OkHttpClient = OkHttpClient()
) {
    private data class R2Config(
        val host: String,
        val bucket: String,
        val accessKeyId: String,
        val secretAccessKey: String
    )

    private fun resolveConfig(): R2Config {
        val endpoint = secureSettings.r2Endpoint ?: error("R2エンドポイントが未設定です")
        val bucket = secureSettings.r2BucketName ?: error("R2バケット名が未設定です")
        val accessKeyId = secureSettings.r2AccessKeyId ?: error("R2アクセスキーIDが未設定です")
        val secretAccessKey = secureSettings.r2SecretAccessKey ?: error("R2シークレットアクセスキーが未設定です")
        val host = endpoint.removePrefix("https://").removePrefix("http://").trimEnd('/')
        return R2Config(host, bucket, accessKeyId, secretAccessKey)
    }

    private fun keyFor(mediaKey: String, xUserId: String) = "$xUserId/$mediaKey.jpg"

    /** アップロード成功時、Web版と同じ相対パス（`/backups/<key>`）を返す。 */
    suspend fun upload(mediaKey: String, xUserId: String, imageBytes: ByteArray): String =
        withContext(Dispatchers.IO) {
            val config = resolveConfig()
            val key = keyFor(mediaKey, xUserId)
            val uriPath = "/${config.bucket}/$key"
            val contentType = "image/jpeg"

            val signed = Sigv4Signer.signPut(
                accessKeyId = config.accessKeyId,
                secretAccessKey = config.secretAccessKey,
                region = "auto",
                host = config.host,
                uriPath = uriPath,
                payload = imageBytes,
                contentType = contentType
            )

            val requestBuilder = Request.Builder()
                .url("https://${config.host}$uriPath")
                .put(imageBytes.toRequestBody(contentType.toMediaType()))
            signed.headers.forEach { (name, value) -> requestBuilder.header(name, value) }

            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("R2アップロードに失敗しました (${response.code}): ${response.body?.string()}")
                }
            }

            "/backups/$key"
        }

    /** クラウドバックアップからの復元用。R2から画像本体をダウンロードして返す。 */
    suspend fun download(mediaKey: String, xUserId: String): ByteArray =
        withContext(Dispatchers.IO) {
            val config = resolveConfig()
            val key = keyFor(mediaKey, xUserId)
            val uriPath = "/${config.bucket}/$key"

            val signed = Sigv4Signer.signGet(
                accessKeyId = config.accessKeyId,
                secretAccessKey = config.secretAccessKey,
                region = "auto",
                host = config.host,
                uriPath = uriPath
            )

            val requestBuilder = Request.Builder()
                .url("https://${config.host}$uriPath")
                .get()
            signed.headers.forEach { (name, value) -> requestBuilder.header(name, value) }

            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("R2ダウンロードに失敗しました (${response.code}): $key")
                }
                val body = response.body ?: throw Exception("R2レスポンスが空です: $key")
                body.bytes()
            }
        }
}
