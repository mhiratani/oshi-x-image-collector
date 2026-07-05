package com.hilamalu.oshixcollector.data.backup

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AWS Signature Version 4（単発PUTのみ対応）の最小実装。
 * Cloudflare R2はS3互換APIのため、`aws-android-sdk`のような大きな依存を足さずに
 * 標準ライブラリ（javax.crypto / java.security）だけで完結させる。
 */
internal object Sigv4Signer {
    private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val DATE_ONLY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)

    data class SignedHeaders(val headers: Map<String, String>)

    /**
     * PUTリクエストの署名済みヘッダーを生成する。
     * [host]は `<bucket>.<account>.r2.cloudflarestorage.com` のようなホスト名（スキームなし）。
     * [uriPath]は `/` から始まるオブジェクトキー（例: `/my-file.jpg`、URLエンコード適用済み想定なし・単純キーのみ対応）。
     */
    fun signPut(
        accessKeyId: String,
        secretAccessKey: String,
        region: String,
        host: String,
        uriPath: String,
        payload: ByteArray,
        contentType: String
    ): SignedHeaders {
        val core = sign(
            method = "PUT",
            accessKeyId = accessKeyId,
            secretAccessKey = secretAccessKey,
            region = region,
            host = host,
            uriPath = uriPath,
            payloadHash = sha256Hex(payload),
            additionalSignedHeaders = mapOf("content-type" to contentType)
        )
        return SignedHeaders(core.headers + ("Content-Type" to contentType))
    }

    /**
     * GETリクエストの署名済みヘッダーを生成する（復元機能でのR2からのダウンロード用）。
     * GETはボディを持たないため、payloadHashは空バイト列のSHA256（SigV4の規定）を使う。
     */
    fun signGet(
        accessKeyId: String,
        secretAccessKey: String,
        region: String,
        host: String,
        uriPath: String
    ): SignedHeaders = sign(
        method = "GET",
        accessKeyId = accessKeyId,
        secretAccessKey = secretAccessKey,
        region = region,
        host = host,
        uriPath = uriPath,
        payloadHash = sha256Hex(ByteArray(0)),
        additionalSignedHeaders = emptyMap()
    )

    private fun sign(
        method: String,
        accessKeyId: String,
        secretAccessKey: String,
        region: String,
        host: String,
        uriPath: String,
        payloadHash: String,
        additionalSignedHeaders: Map<String, String>
    ): SignedHeaders {
        val now = Instant.now()
        val amzDate = DATE_FORMAT.format(now)
        val dateStamp = DATE_ONLY_FORMAT.format(now)

        val canonicalHeadersMap = sortedMapOf(
            "host" to host,
            "x-amz-content-sha256" to payloadHash,
            "x-amz-date" to amzDate
        ).apply { putAll(additionalSignedHeaders) }
        val signedHeaders = canonicalHeadersMap.keys.joinToString(";")
        val canonicalHeaders = canonicalHeadersMap.entries.joinToString("") { (k, v) -> "$k:$v\n" }

        val canonicalRequest = listOf(
            method,
            uriPath,
            "", // query string (未使用)
            canonicalHeaders,
            signedHeaders,
            payloadHash
        ).joinToString("\n")

        val credentialScope = "$dateStamp/$region/s3/aws4_request"
        val stringToSign = listOf(
            "AWS4-HMAC-SHA256",
            amzDate,
            credentialScope,
            sha256Hex(canonicalRequest.toByteArray(Charsets.UTF_8))
        ).joinToString("\n")

        val signingKey = signingKey(secretAccessKey, dateStamp, region)
        val signature = hmacHex(signingKey, stringToSign)

        val authorization = "AWS4-HMAC-SHA256 " +
            "Credential=$accessKeyId/$credentialScope, " +
            "SignedHeaders=$signedHeaders, " +
            "Signature=$signature"

        return SignedHeaders(
            mapOf(
                "Authorization" to authorization,
                "x-amz-content-sha256" to payloadHash,
                "x-amz-date" to amzDate
            )
        )
    }

    private fun signingKey(secretAccessKey: String, dateStamp: String, region: String): ByteArray {
        val kSecret = "AWS4$secretAccessKey".toByteArray(Charsets.UTF_8)
        val kDate = hmac(kSecret, dateStamp)
        val kRegion = hmac(kDate, region)
        val kService = hmac(kRegion, "s3")
        return hmac(kService, "aws4_request")
    }

    private fun hmac(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun hmacHex(key: ByteArray, data: String): String = toHex(hmac(key, data))

    private fun sha256Hex(bytes: ByteArray): String = toHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    // "%02x".format(byte) sign-extends negative bytes (e.g. 0xff -> "-1"), corrupting the
    // signature for any byte with the high bit set. Mask to unsigned before formatting.
    private fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
