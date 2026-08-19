package com.hilamalu.oshixcollector.data.notification

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * 予約時刻に起きて、おすすめの1枚を通知するワーカー。
 * 実行のたびに自分で次回分を予約し直す（[NotificationScheduler.scheduleNext]）ため、
 * 「1日N回・時間帯内でランダム」という不定間隔のスケジュールを定期実行なしで表現できる。
 */
class OshiNotificationWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val settings = NotificationSettings(applicationContext).snapshot()
        // 予約後にOFFにされた場合。次回の予約もしないのでここで打ち止めになる
        if (!settings.enabled) return Result.success()

        try {
            NotificationRecommender(applicationContext).notifyRandom(settings)
        } catch (e: Exception) {
            // 1回失敗しても次回の予約は行う（リトライすると予約時刻から外れた通知になるため再試行しない）
            Log.w(TAG, "おすすめ通知の表示に失敗しました", e)
        }

        NotificationScheduler.scheduleNext(applicationContext, settings)
        return Result.success()
    }

    private companion object {
        const val TAG = "OshiNotificationWorker"
    }
}
