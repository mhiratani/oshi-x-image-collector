package com.hilamalu.oshixcollector.data.settings

import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 機種変更時の設定引き継ぎ用エクスポート/インポート。
 * SecureSettingsの全項目をJSONにまとめ、パスフレーズから導出した鍵(PBKDF2-HMAC-SHA256)で
 * AES-256-GCM暗号化したファイルとして受け渡す。クラウドを経由しないオフライン方式。
 */
object SettingsTransfer {

    /** 平文側のペイロード。項目追加時はここに足せば自動でエクスポート対象になる。 */
    @Serializable
    data class Payload(
        val xBearerToken: String? = null,
        val r2BucketName: String? = null,
        val r2AccountId: String? = null,
        val r2AccessKeyId: String? = null,
        val r2SecretAccessKey: String? = null,
        val r2Endpoint: String? = null,
        val firebaseApiKey: String? = null,
        val firebaseProjectId: String? = null,
        val firebaseAppId: String? = null,
        val firebaseWebClientId: String? = null,
    )

    /** ファイルに書き出す暗号化エンベロープ。saltとivは毎回ランダム生成する。 */
    @Serializable
    private data class Envelope(
        val format: String = FORMAT,
        val version: Int = VERSION,
        val iterations: Int,
        val salt: String,
        val iv: String,
        val data: String,
    )

    class InvalidPassphraseException : Exception("パスフレーズが違うか、ファイルが壊れています")
    class InvalidFileException : Exception("このアプリの設定ファイルではありません")

    private const val FORMAT = "oshi-x-collector-settings"
    private const val VERSION = 1
    private const val PBKDF2_ITERATIONS = 600_000
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun payloadFrom(settings: SecureSettings) = Payload(
        xBearerToken = settings.xBearerToken,
        r2BucketName = settings.r2BucketName,
        r2AccountId = settings.r2AccountId,
        r2AccessKeyId = settings.r2AccessKeyId,
        r2SecretAccessKey = settings.r2SecretAccessKey,
        r2Endpoint = settings.r2Endpoint,
        firebaseApiKey = settings.firebaseApiKey,
        firebaseProjectId = settings.firebaseProjectId,
        firebaseAppId = settings.firebaseAppId,
        firebaseWebClientId = settings.firebaseWebClientId,
    )

    fun applyTo(payload: Payload, settings: SecureSettings) {
        settings.xBearerToken = payload.xBearerToken
        settings.r2BucketName = payload.r2BucketName
        settings.r2AccountId = payload.r2AccountId
        settings.r2AccessKeyId = payload.r2AccessKeyId
        settings.r2SecretAccessKey = payload.r2SecretAccessKey
        settings.r2Endpoint = payload.r2Endpoint
        settings.firebaseApiKey = payload.firebaseApiKey
        settings.firebaseProjectId = payload.firebaseProjectId
        settings.firebaseAppId = payload.firebaseAppId
        settings.firebaseWebClientId = payload.firebaseWebClientId
    }

    fun encrypt(payload: Payload, passphrase: CharArray): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(json.encodeToString(Payload.serializer(), payload).toByteArray())
        val envelope = Envelope(
            iterations = PBKDF2_ITERATIONS,
            salt = Base64.encodeToString(salt, Base64.NO_WRAP),
            iv = Base64.encodeToString(iv, Base64.NO_WRAP),
            data = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
        )
        return json.encodeToString(Envelope.serializer(), envelope).toByteArray()
    }

    fun decrypt(fileBytes: ByteArray, passphrase: CharArray): Payload {
        val envelope = try {
            json.decodeFromString(Envelope.serializer(), fileBytes.decodeToString())
        } catch (e: Exception) {
            throw InvalidFileException()
        }
        if (envelope.format != FORMAT) throw InvalidFileException()
        val salt = Base64.decode(envelope.salt, Base64.NO_WRAP)
        val iv = Base64.decode(envelope.iv, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            deriveKey(passphrase, salt, envelope.iterations),
            GCMParameterSpec(GCM_TAG_BITS, iv)
        )
        val plaintext = try {
            cipher.doFinal(Base64.decode(envelope.data, Base64.NO_WRAP))
        } catch (e: AEADBadTagException) {
            throw InvalidPassphraseException()
        }
        return json.decodeFromString(Payload.serializer(), plaintext.decodeToString())
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int = PBKDF2_ITERATIONS): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec)
        return SecretKeySpec(key.encoded, "AES")
    }
}
