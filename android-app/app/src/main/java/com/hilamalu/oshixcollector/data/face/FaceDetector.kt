package com.hilamalu.oshixcollector.data.face

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * ローカル保存済み画像に顔が写っているかをML Kit（オンデバイス、Play Services経由の
 * unbundledモデル）で判定する。Web版の[frontend/worker/faceDetect.js]（BlazeFace）と
 * 同じ役割（顔フィルター用のis_face判定）をAndroid側で担う。
 * 実装はoshi-wallの `data/focal/FocalPointDetector.kt` のパターンを踏襲する。
 */
object FaceDetector {

    sealed interface Result {
        /** ML Kitは生の確率値を公開していないため、confidenceは検出有無から作る疑似値（1f/0f）。 */
        data class Detected(val isFace: Boolean, val confidence: Float) : Result

        /** モデル未取得などで判定を実行できなかった（永続化せず次回に再試行）。 */
        data object Unavailable : Result
    }

    // 検出精度と速度のバランス上これ以上の解像度は不要。デコード時の長辺上限。
    private const val MAX_DETECT_DIMENSION = 1280

    // クライアントは初回使用時に生成してプロセス寿命で使い回す（生成コストが高い）。
    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.1f)
                .build()
        )
    }

    suspend fun detect(file: File): Result {
        val bitmap = decodeSampledBitmap(file) ?: return Result.Unavailable
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val faces = detector.process(image).awaitResult()
            return Result.Detected(isFace = faces.isNotEmpty(), confidence = if (faces.isNotEmpty()) 1f else 0f)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // unbundled な ML Kit は Task の失敗だけでなく同期的にも例外を投げうる
            // （実例: InputImage.fromBitmap 内部の NPE）。全て検出不能として扱う。
            return Result.Unavailable
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel() }
    }

    private fun decodeSampledBitmap(file: File): Bitmap? {
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        file.inputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var inSampleSize = 1
        while (bounds.outWidth / inSampleSize > MAX_DETECT_DIMENSION ||
            bounds.outHeight / inSampleSize > MAX_DETECT_DIMENSION
        ) {
            inSampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
        return try {
            file.inputStream().use { BitmapFactory.decodeStream(it, null, options) }
        } catch (e: Exception) {
            null
        }
    }
}
