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
 * キー命名は [frontend/worker/backup.js] と同じ `<x_user_id>/<media_key>.<ext>`
 * （拡張子はX CDNのURLから取得。pngやgifもWeb版と同じキー・Content-Typeで保存する）。
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

    /** [frontend/worker/backup.js]と同じく、X CDNのURLパスから実際の拡張子を取り出す（不明ならjpg）。 */
    private fun extFor(xCdnUrl: String): String =
        xCdnUrl.substringBefore('?').substringAfterLast('/')
            .substringAfterLast('.', "").lowercase().ifEmpty { "jpg" }

    /** アップロード成功時、Web版と同じ相対パス（`/backups/<key>`）を返す。 */
    suspend fun upload(mediaKey: String, xUserId: String, xCdnUrl: String, imageBytes: ByteArray): String =
        withContext(Dispatchers.IO) {
            val config = resolveConfig()
            val ext = extFor(xCdnUrl)
            val key = "$xUserId/$mediaKey.$ext"
            val uriPath = "/${config.bucket}/$key"
            val contentType = CONTENT_TYPES[ext] ?: "application/octet-stream"

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

            "$PUBLIC_PATH_PREFIX$key"
        }

    /**
     * クラウドバックアップからの復元用。R2から画像本体をダウンロードして返す。
     * キーはミラー時に記録した`r2_backup_url`（`/backups/<key>`）から復元する。
     * 命名規則からの再構築だと、Web版がpng等でバックアップした画像とキーが一致しないため。
     */
    suspend fun download(r2BackupUrl: String): ByteArray =
        withContext(Dispatchers.IO) {
            val config = resolveConfig()
            val key = r2BackupUrl.removePrefix(PUBLIC_PATH_PREFIX)
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

    private companion object {
        /** Web版の配信ルート（app/backups/[...path]）に合わせた公開パスの接頭辞。 */
        const val PUBLIC_PATH_PREFIX = "/backups/"

        /** [frontend/worker/backup.js]のCONTENT_TYPESと同じ対応表。 */
        val CONTENT_TYPES = mapOf(
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "png" to "image/png",
            "gif" to "image/gif",
            "webp" to "image/webp"
        )
    }
}
