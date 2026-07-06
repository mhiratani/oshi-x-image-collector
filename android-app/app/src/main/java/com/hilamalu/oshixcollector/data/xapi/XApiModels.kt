package com.hilamalu.oshixcollector.data.xapi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UserLookupResponse(val data: UserDto? = null)

@Serializable
data class UserDto(val id: String, val username: String)

@Serializable
data class TweetsResponse(
    val data: List<TweetDto>? = null,
    val includes: IncludesDto? = null,
    val meta: MetaDto? = null
)

@Serializable
data class TweetDto(
    val id: String,
    @SerialName("created_at") val createdAt: String,
    val attachments: AttachmentsDto? = null
)

@Serializable
data class AttachmentsDto(@SerialName("media_keys") val mediaKeys: List<String>? = null)

@Serializable
data class IncludesDto(val media: List<MediaDto>? = null)

@Serializable
data class MediaDto(
    @SerialName("media_key") val mediaKey: String,
    val type: String,
    val url: String? = null
)

@Serializable
data class MetaDto(
    @SerialName("newest_id") val newestId: String? = null,
    @SerialName("oldest_id") val oldestId: String? = null,
    @SerialName("next_token") val nextToken: String? = null
)

/** [frontend/worker/xapi.js] の fetchPhotoMedia が返す1件分に相当。 */
data class PhotoMedia(
    val mediaKey: String,
    val tweetId: String,
    val url: String,
    val postedAt: Long
)

data class FetchPhotoMediaResult(
    val media: List<PhotoMedia>,
    val newestId: String?,
    val oldestId: String? = null,
    val exhausted: Boolean
)


class XApiRateLimitedException(message: String) : Exception(message)
