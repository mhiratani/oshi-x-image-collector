package com.hilamalu.oshixcollector.data.xapi

import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * X API v2 クライアント（Bearer Token 認証）。
 * [frontend/worker/xapi.js] のKotlin移植（ユーザー自身のBearer Tokenで叩く点が異なる）。
 */
class XApiClient(
    private val bearerToken: String,
    private val httpClient: OkHttpClient = OkHttpClient()
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun request(path: String, params: Map<String, String?>): Request {
        val urlBuilder = "$API_BASE$path".toHttpUrl().newBuilder()
        params.forEach { (key, value) -> if (value != null) urlBuilder.addQueryParameter(key, value) }
        return Request.Builder()
            .url(urlBuilder.build())
            .header("Authorization", "Bearer $bearerToken")
            .build()
    }

    private fun execute(request: Request): String {
        httpClient.newCall(request).execute().use { response ->
            if (response.code == 429) {
                val reset = response.header("x-rate-limit-reset")
                throw XApiRateLimitedException("rate limited (reset epoch: ${reset ?: "unknown"})")
            }
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw Exception("X API ${response.code}: $body")
            }
            return body
        }
    }

    /** screen_name (`@`なし) を x_user_id に解決する。見つからない場合は null。 */
    suspend fun resolveUserId(screenName: String): String? = withContext(Dispatchers.IO) {
        val request = request("/users/by/username/$screenName", emptyMap())
        val body = execute(request)
        json.decodeFromString<UserLookupResponse>(body).data?.id
    }

    /**
     * あるユーザーの新着ツイートから photo メディアを取得する。
     * [sinceId]より新しいツイートのみ、最大[maxPages]ページ（1ページ=最大100件）取得する。
     */
    suspend fun fetchPhotoMedia(
        userId: String,
        sinceId: String?,
        maxPages: Int = 3
    ): FetchPhotoMediaResult = withContext(Dispatchers.IO) {
        val media = mutableListOf<PhotoMedia>()
        var newestId: String? = null
        var exhausted = false
        var paginationToken: String? = null

        for (page in 0 until maxPages) {
            val params = mapOf(
                "max_results" to "100",
                "exclude" to "retweets",
                "expansions" to "attachments.media_keys",
                "media.fields" to "url,type",
                "tweet.fields" to "created_at,attachments",
                "since_id" to sinceId,
                "pagination_token" to paginationToken
            )
            val response = json.decodeFromString<TweetsResponse>(
                execute(request("/users/$userId/tweets", params))
            )

            if (response.meta?.newestId != null && newestId == null) newestId = response.meta.newestId

            val tweets = response.data
            if (tweets.isNullOrEmpty()) {
                exhausted = true
                break
            }

            val mediaByKey = response.includes?.media.orEmpty().associateBy { it.mediaKey }
            for (tweet in tweets) {
                val postedAt = Instant.parse(tweet.createdAt).toEpochMilli()
                for (key in tweet.attachments?.mediaKeys.orEmpty()) {
                    val m = mediaByKey[key]
                    if (m?.type == "photo" && m.url != null) {
                        media += PhotoMedia(mediaKey = m.mediaKey, tweetId = tweet.id, url = m.url, postedAt = postedAt)
                    }
                }
            }

            paginationToken = response.meta?.nextToken
            if (paginationToken == null) {
                exhausted = true
                break
            }
        }

        FetchPhotoMediaResult(media = media, newestId = newestId, exhausted = exhausted)
    }

    companion object {
        private const val API_BASE = "https://api.twitter.com/2"
    }
}
