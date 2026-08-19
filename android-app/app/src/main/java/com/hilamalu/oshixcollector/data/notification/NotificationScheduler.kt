package com.hilamalu.oshixcollector.data.notification

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * おすすめ通知の予約をWorkManagerに登録する。
 *
 * 「N時間おき」ではなく次の1回だけを都度予約する方式（実行後に[OshiNotificationWorker]が
 * 次回を予約する）。時間帯内のランダムな時刻という不定間隔を素直に表現でき、
 * 設定変更時も次の1件を置き換えるだけで済む。
 * WorkManagerは予約を自前のDBに永続化するため、アプリ終了後・端末再起動後も予約は残る。
 */
object NotificationScheduler {
    private const val UNIQUE_WORK_NAME = "oshi_recommendation_notification"

    /**
     * 設定に合わせて予約を張り直す。ONなら次回1件を予約し直し、OFFなら予約を取り消す。
     * 設定画面での変更のたびに呼ぶ。
     */
    suspend fun sync(context: Context) {
        val settings = NotificationSettings(context).snapshot()
        if (settings.enabled) {
            scheduleNext(context, settings)
        } else {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }

    /**
     * アプリ起動時の保険。既存の予約があればそれを尊重し（[ExistingWorkPolicy.KEEP]）、
     * 何らかの理由で予約が消えていた場合だけ張り直す。
     */
    suspend fun ensureScheduled(context: Context) {
        val settings = NotificationSettings(context).snapshot()
        if (!settings.enabled) return
        enqueue(context, settings, ExistingWorkPolicy.KEEP)
    }

    /** 次回の通知時刻を計算して予約する（既存の予約は置き換える）。 */
    fun scheduleNext(context: Context, settings: NotificationSettings.Snapshot) {
        enqueue(context, settings, ExistingWorkPolicy.REPLACE)
    }

    private fun enqueue(
        context: Context,
        settings: NotificationSettings.Snapshot,
        policy: ExistingWorkPolicy
    ) {
        val now = System.currentTimeMillis()
        val delayMillis = (NotificationSchedule.nextFireAt(now, settings) - now).coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<OshiNotificationWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK_NAME, policy, request)
    }
}
