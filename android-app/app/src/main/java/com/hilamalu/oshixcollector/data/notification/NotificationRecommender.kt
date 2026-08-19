package com.hilamalu.oshixcollector.data.notification

import android.content.Context
import com.hilamalu.oshixcollector.data.db.AppDatabase

/**
 * 設定に合う画像を1枚ランダムに選んで通知する処理本体。
 * 定期通知（[OshiNotificationWorker]）と設定画面の「今すぐテスト通知」の両方から使う。
 */
class NotificationRecommender(context: Context) {
    private val db = AppDatabase.getDatabase(context)
    private val notifier = OshiNotifier(context)

    /** 通知を出せたか。出せなかった場合はその理由。設定画面のテスト通知でそのまま文言に使う。 */
    enum class Result {
        /** 通知を表示した。 */
        NOTIFIED,

        /** 条件に合う画像が1枚も無い（対象アカウント未収集・お気に入り0件・顔ありの画像が無いなど）。 */
        NO_CANDIDATE,

        /** 端末側で通知が許可されていない。 */
        NOT_PERMITTED
    }

    suspend fun notifyRandom(settings: NotificationSettings.Snapshot): Result {
        // 追跡中のアカウントに限る（同期停止中のアカウントも収集済み画像は表示対象なので除外しない）
        val accounts = db.targetAccountDao().getAll()
        val trackedUserIds = accounts.mapNotNull { it.xUserId }.toSet()
        // 選択されたアカウントのうち、既に追跡対象から消えているものは無視する
        val targetUserIds = settings.targetUserIds.intersect(trackedUserIds)
        val allAccounts = targetUserIds.isEmpty()

        val asset = db.mediaAssetDao().pickRandom(
            favoritesOnly = settings.favoritesOnly,
            faceOnly = settings.faceOnly,
            allAccounts = allAccounts,
            xUserIds = targetUserIds.toList()
        ) ?: return Result.NO_CANDIDATE

        val screenName = accounts.firstOrNull { it.xUserId == asset.xUserId }?.screenName
        return if (notifier.notifyRecommendation(asset, screenName)) Result.NOTIFIED
        else Result.NOT_PERMITTED
    }
}
