package com.hilamalu.oshixcollector.tweet

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * おすすめ通知のタップ先。画面は持たず、[TweetLauncher]でXアプリ（無ければブラウザ）へ
 * 中継してすぐ終了する。
 *
 * Android 12以降はBroadcastReceiver/Serviceを経由する通知トランポリンが禁止されているため、
 * 「Xアプリの有無で開き方を変える」処理を挟むにはこのような透明Activityが必要になる。
 * PendingIntentに直接 Xアプリ名指しのIntentを入れてしまうと、後からXアプリを
 * アンインストールした端末でタップしても何も起きなくなる。
 */
class TweetOpenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra(EXTRA_TWEET_ID)?.let { tweetId ->
            // この Activity は即 finish するため、開いた先がこのタスクに積まれないよう別タスクで起動する
            TweetLauncher.open(this, tweetId, Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        finish()
    }

    companion object {
        const val EXTRA_TWEET_ID = "tweet_id"
    }
}
