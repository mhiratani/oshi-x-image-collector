package com.hilamalu.oshixcollector.tweet

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * ツイートを X公式アプリ（無ければブラウザ）で開く。
 * 拡大表示の「𝕏↗」ボタンと、おすすめ通知のタップ（[TweetOpenActivity]）の両方から使う。
 *
 * Xアプリへの渡し方は次の優先順で試す:
 *   1. https を X公式アプリ(com.twitter.android)に名指しで渡す
 *      （現行のXアプリは twitter://status?id= を受理するものの解釈できず
 *        ホームに落ちるだけのため、https 名指しを最優先にする。
 *        X v12.9.1 / Pixel 9 実機で確認済み）
 *   2. twitter:// スキーム（x.com ドメイン未対応の旧Twitterアプリ救済）
 *   3. どちらもダメならブラウザで https を開く（Xアプリ未インストール時など）
 * 1・3で使う https はユーザー名不要の正規形 /i/status/<id>（App Link 対象）にする。
 * /i/web/status/ はWeb専用リダイレクトでXアプリが受け取らないため使わない。
 *
 * 名指し起動には AndroidManifest.xml の <queries> 宣言が必要（Android 11+のパッケージ可視性対策）。
 */
object TweetLauncher {
    private const val X_APP_PACKAGE = "com.twitter.android"

    /**
     * [tweetId]の投稿を開く。[extraFlags]には、Activity以外のコンテキストから呼ぶ場合の
     * [Intent.FLAG_ACTIVITY_NEW_TASK]などを渡す。
     */
    fun open(context: Context, tweetId: String, extraFlags: Int = 0) {
        val webUri = Uri.parse("https://x.com/i/status/$tweetId")

        // 1. https を Xアプリに名指しで渡す（現行Xアプリでポスト詳細に直行する唯一の形式）
        val appWebIntent = Intent(Intent.ACTION_VIEW, webUri)
            .setPackage(X_APP_PACKAGE)
            .addFlags(extraFlags)
        if (appWebIntent.resolveActivity(context.packageManager) != null) {
            context.startActivity(appWebIntent)
            return
        }

        // 2. Xアプリのディープリンクスキーム（x.com を知らない旧アプリ向け）
        val schemeIntent = Intent(Intent.ACTION_VIEW, Uri.parse("twitter://status?id=$tweetId"))
            .addFlags(extraFlags)
        if (schemeIntent.resolveActivity(context.packageManager) != null) {
            context.startActivity(schemeIntent)
            return
        }

        // 3. ブラウザへフォールバック
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, webUri).addFlags(extraFlags))
        } catch (_: ActivityNotFoundException) {
            // 開けるアプリが一切無い端末では何もしない
        }
    }
}
