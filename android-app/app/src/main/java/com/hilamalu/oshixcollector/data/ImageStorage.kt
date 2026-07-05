package com.hilamalu.oshixcollector.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Xから取得した画像を端末ローカル（`filesDir/media/`）に保存・読み込みする。 */
class ImageStorage(
    private val context: Context,
    private val httpClient: OkHttpClient = OkHttpClient()
) {
    private val mediaDir: File
        get() = File(context.filesDir, "media").apply { mkdirs() }

    fun fileFor(mediaKey: String): File = File(mediaDir, "$mediaKey.jpg")

    /** [url]から画像をダウンロードしてローカルに保存し、保存先の絶対パスを返す。 */
    suspend fun download(mediaKey: String, url: String): String = withContext(Dispatchers.IO) {
        val destination = fileFor(mediaKey)
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("画像のダウンロードに失敗しました (${response.code}): $url")
            }
            val body = response.body ?: throw Exception("画像のレスポンスが空です: $url")
            destination.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
        destination.absolutePath
    }

    fun delete(mediaKey: String) {
        fileFor(mediaKey).delete()
    }
}
