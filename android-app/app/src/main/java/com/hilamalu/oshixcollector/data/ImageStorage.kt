package com.hilamalu.oshixcollector.data

import android.content.Context
import android.graphics.BitmapFactory
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

    /** R2などから取得済みのバイト列をローカルに保存し、保存先の絶対パスを返す（復元機能用）。 */
    suspend fun saveBytes(mediaKey: String, bytes: ByteArray): String = withContext(Dispatchers.IO) {
        val destination = fileFor(mediaKey)
        destination.outputStream().use { out -> out.write(bytes) }
        destination.absolutePath
    }

    /**
     * [path]の画像の縦横比（幅÷高さ）を返す。masonry表示でタイルの高さを描画前に確定させるために使う。
     * ピクセルはデコードせず境界情報だけ読むため1枚あたり1ms未満で済む。
     * ファイルが無い・画像として読めない場合はnull。
     */
    suspend fun aspectRatio(path: String): Float? = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.exists()) return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            file.inputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
        } catch (e: Exception) {
            return@withContext null
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        bounds.outWidth.toFloat() / bounds.outHeight.toFloat()
    }
}
