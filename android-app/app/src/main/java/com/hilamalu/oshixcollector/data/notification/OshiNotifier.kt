package com.hilamalu.oshixcollector.data.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.hilamalu.oshixcollector.R
import com.hilamalu.oshixcollector.data.db.MediaAssetEntity
import com.hilamalu.oshixcollector.tweet.TweetOpenActivity
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * 抽選した1枚を「おすすめ」としてAndroidの通知に出す。
 * タップすると[TweetOpenActivity]経由で元ツイートがXアプリで開く。
 */
class OshiNotifier(private val context: Context) {

    /**
     * [asset]の画像を貼った通知を出す。通知が許可されていない場合は何もしない。
     * 表示に失敗しても収集機能側には影響させないため、例外は投げずfalseを返す。
     */
    fun notifyRecommendation(asset: MediaAssetEntity, screenName: String?): Boolean {
        val manager = NotificationManagerCompat.from(context)
        // Android 13+ で POST_NOTIFICATIONS が未許可、または設定でOFFにされている場合は通知できない
        if (!manager.areNotificationsEnabled()) return false
        createChannel()

        val picture = asset.localImagePath?.let { decodeScaled(it) }
        val title = screenName?.let { context.getString(R.string.notification_title_with_account, it) }
            ?: context.getString(R.string.notification_title)
        val postedAt = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(asset.postedAt))

        val contentIntent = PendingIntent.getActivity(
            context,
            asset.mediaKey.hashCode(),
            Intent(context, TweetOpenActivity::class.java)
                .putExtra(TweetOpenActivity.EXTRA_TWEET_ID, asset.tweetId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.notification_body, postedAt))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        if (picture != null) {
            builder.setLargeIcon(picture)
                // 展開時は大きく見せ、折りたたみ時のサムネイル（largeIcon）は消す標準的な作法
                .setStyle(NotificationCompat.BigPictureStyle().bigPicture(picture).bigLargeIcon(null as Bitmap?))
        }

        return try {
            // 常に同じIDで出し、未読の通知が溜まらないようにする（最新の1枚だけ残る）
            manager.notify(NOTIFICATION_ID, builder.build())
            true
        } catch (_: SecurityException) {
            // areNotificationsEnabled()の判定後に権限が取り消された場合
            false
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.notification_channel_description)
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    /**
     * 通知に載せるために画像を縮小して読み込む。原寸のままだと通知に渡せるBitmapのサイズ上限
     * （1MB程度）を超えて表示されないことがあるため、[ImageStorage]と同じく境界情報を先に読んで
     * inSampleSizeを決める。
     */
    private fun decodeScaled(path: String): Bitmap? {
        val file = File(path)
        if (!file.exists()) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            file.inputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sampleSize = 1
            while (
                bounds.outWidth / sampleSize > MAX_PICTURE_EDGE_PX ||
                bounds.outHeight / sampleSize > MAX_PICTURE_EDGE_PX
            ) {
                sampleSize *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            file.inputStream().use { BitmapFactory.decodeStream(it, null, options) }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val CHANNEL_ID = "oshi_recommendation"

        /** 通知は1件だけ表示する方針のため固定ID。 */
        private const val NOTIFICATION_ID = 1001

        /** 通知に載せる画像の長辺の上限（px）。 */
        private const val MAX_PICTURE_EDGE_PX = 1024
    }
}
