package com.hilamalu.oshixcollector.data.transfer

import com.hilamalu.oshixcollector.data.db.MediaAssetEntity
import com.hilamalu.oshixcollector.data.db.TargetAccountEntity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 機種変更用データパックの往復テスト。
 * ここが壊れると収集済みの画像を復元できなくなるため、Context/Roomに依存しない設計にして
 * JVM単体テストで検証している。
 */
class DataPackTransferTest {

    private fun account(screenName: String) = TargetAccountEntity(
        screenName = screenName,
        xUserId = "100$screenName",
        lastFetchedId = "999",
        lastCheckedAt = 1_700_000_000_000,
        createdAt = 1_600_000_000_000,
        backfillCursor = "cursor-$screenName",
        backfillDone = false,
        syncPaused = false
    )

    private fun media(mediaKey: String, localPath: String?) = MediaAssetEntity(
        mediaKey = mediaKey,
        tweetId = "tweet-$mediaKey",
        xUserId = "1001",
        xCdnUrl = "https://pbs.twimg.com/media/$mediaKey.jpg",
        localImagePath = localPath,
        r2BackupUrl = "https://r2.example.com/$mediaKey.jpg",
        backupAttempts = 2,
        postedAt = 1_700_000_000_000,
        createdAt = 1_700_000_001_000,
        isFace = true,
        faceConfidence = 1.0f,
        faceReviewed = true,
        isFavorite = true
    )

    private fun imageEntry(mediaKey: String, bytes: ByteArray) = DataPackTransfer.ImageEntry(
        mediaKey = mediaKey,
        sizeBytes = bytes.size.toLong(),
        openStream = { ByteArrayInputStream(bytes) }
    )

    /** 取り込み側の受け皿。実機では ImageStorage.fileFor が返す絶対パスに相当する。 */
    private class FakeSink : DataPackTransfer.ImageSink {
        val saved = linkedMapOf<String, ByteArray>()
        override suspend fun save(mediaKey: String, bytes: ByteArray): String {
            saved[mediaKey] = bytes
            return "/data/user/0/app/files/media/$mediaKey.jpg"
        }
    }

    private fun writePack(
        accounts: List<TargetAccountEntity>,
        media: List<MediaAssetEntity>,
        images: List<DataPackTransfer.ImageEntry>
    ): ByteArray = runBlocking {
        ByteArrayOutputStream().also { out ->
            DataPackTransfer.write(out, accounts, media, images, exportedAt = 1_700_000_000_000)
        }.toByteArray()
    }

    @Test
    fun `書き出して取り込むとアカウント・メタデータ・画像が復元される`() = runBlocking {
        val imageBytes = mapOf(
            "key1" to byteArrayOf(1, 2, 3, 4, 5),
            "key2" to ByteArray(1024) { it.toByte() }
        )
        val bytes = writePack(
            accounts = listOf(account("alice"), account("bob")),
            media = listOf(media("key1", "/old/device/path/key1.jpg"), media("key2", null)),
            images = imageBytes.map { (k, v) -> imageEntry(k, v) }
        )

        val sink = FakeSink()
        val restoredPaths = mutableMapOf<String, String>()
        var seenAccounts: List<TargetAccountEntity> = emptyList()
        var seenMedia: List<MediaAssetEntity> = emptyList()

        val result = DataPackTransfer.read(
            input = ByteArrayInputStream(bytes),
            onMetadata = { _, accounts, media -> seenAccounts = accounts; seenMedia = media },
            sink = sink,
            onImageRestored = { mediaKey, path -> restoredPaths[mediaKey] = path }
        )

        assertEquals(2, seenAccounts.size)
        assertEquals("alice", seenAccounts[0].screenName)
        assertEquals("cursor-alice", seenAccounts[0].backfillCursor)

        assertEquals(2, seenMedia.size)
        assertEquals(2, result.imagesRestored)
        assertEquals(0, result.imagesFailed)
        assertEquals(setOf("key1", "key2"), sink.saved.keys)
        imageBytes.forEach { (k, v) -> assertTrue(v.contentEquals(sink.saved.getValue(k))) }
        assertEquals(setOf("key1", "key2"), restoredPaths.keys)
    }

    @Test
    fun `端末固有のlocalImagePathは書き出されない`() = runBlocking {
        val bytes = writePack(
            accounts = emptyList(),
            media = listOf(media("key1", "/data/user/0/app/files/media/key1.jpg")),
            images = listOf(imageEntry("key1", byteArrayOf(9)))
        )

        var seenMedia: List<MediaAssetEntity> = emptyList()
        DataPackTransfer.read(
            input = ByteArrayInputStream(bytes),
            onMetadata = { _, _, media -> seenMedia = media },
            sink = FakeSink(),
            onImageRestored = { _, _ -> }
        )

        // 絶対パスは端末ごとに違うため、取り込み側が貼り直せるようnullで運ばれる必要がある
        assertNull(seenMedia.single().localImagePath)
        // それ以外のフィールドは保持される
        assertEquals(true, seenMedia.single().isFavorite)
        assertEquals("https://r2.example.com/key1.jpg", seenMedia.single().r2BackupUrl)
    }

    @Test
    fun `メタデータは画像より先に適用される`() = runBlocking {
        val order = mutableListOf<String>()
        val bytes = writePack(
            accounts = listOf(account("alice")),
            media = listOf(media("key1", null)),
            images = listOf(imageEntry("key1", byteArrayOf(1)))
        )

        DataPackTransfer.read(
            input = ByteArrayInputStream(bytes),
            onMetadata = { _, _, _ -> order += "metadata" },
            sink = object : DataPackTransfer.ImageSink {
                override suspend fun save(mediaKey: String, bytes: ByteArray): String {
                    order += "image"
                    return "/path/$mediaKey.jpg"
                }
            },
            onImageRestored = { _, _ -> }
        )

        // この順序が崩れると、取り込み側のパス貼り直しが対象行を見つけられず空振りする
        assertEquals(listOf("metadata", "image"), order)
    }

    @Test
    fun `画像が0枚でもメタデータは適用される`() = runBlocking {
        val bytes = writePack(listOf(account("alice")), listOf(media("key1", null)), emptyList())

        var applied = false
        val result = DataPackTransfer.read(
            input = ByteArrayInputStream(bytes),
            onMetadata = { _, _, _ -> applied = true },
            sink = FakeSink(),
            onImageRestored = { _, _ -> }
        )

        assertTrue(applied)
        assertEquals(0, result.imagesRestored)
    }

    @Test
    fun `manifestの件数が実体と一致する`() = runBlocking {
        val images = listOf(imageEntry("key1", ByteArray(10)), imageEntry("key2", ByteArray(20)))
        val bytes = writePack(listOf(account("alice")), listOf(media("key1", null), media("key2", null)), images)

        val result = DataPackTransfer.read(
            input = ByteArrayInputStream(bytes),
            onMetadata = { _, _, _ -> },
            sink = FakeSink(),
            onImageRestored = { _, _ -> }
        )

        assertEquals(DataPackTransfer.FORMAT, result.manifest.format)
        assertEquals(1, result.manifest.accountCount)
        assertEquals(2, result.manifest.mediaCount)
        assertEquals(2, result.manifest.imageCount)
        assertEquals(30L, result.manifest.imageBytesTotal)
    }

    @Test
    fun `別形式のZIPはInvalidFileExceptionになる`() = runBlocking {
        val foreign = ByteArrayOutputStream().also { out ->
            java.util.zip.ZipOutputStream(out).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
                zip.write("""{"format":"something-else","version":1}""".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()

        try {
            DataPackTransfer.read(
                input = ByteArrayInputStream(foreign),
                onMetadata = { _, _, _ -> },
                sink = FakeSink(),
                onImageRestored = { _, _ -> }
            )
            fail("InvalidFileException が投げられるべき")
        } catch (e: DataPackTransfer.InvalidFileException) {
            // 期待どおり
        }
    }

    @Test
    fun `新しいversionのファイルはUnsupportedVersionExceptionになる`() = runBlocking {
        val future = ByteArrayOutputStream().also { out ->
            java.util.zip.ZipOutputStream(out).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
                zip.write("""{"format":"${DataPackTransfer.FORMAT}","version":99}""".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()

        try {
            DataPackTransfer.read(
                input = ByteArrayInputStream(future),
                onMetadata = { _, _, _ -> },
                sink = FakeSink(),
                onImageRestored = { _, _ -> }
            )
            fail("UnsupportedVersionException が投げられるべき")
        } catch (e: DataPackTransfer.UnsupportedVersionException) {
            // 期待どおり
        }
    }

    @Test
    fun `manifestが無いZIPはInvalidFileExceptionになる`() = runBlocking {
        val noManifest = ByteArrayOutputStream().also { out ->
            java.util.zip.ZipOutputStream(out).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("accounts.json"))
                zip.write("[]".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()

        try {
            DataPackTransfer.read(
                input = ByteArrayInputStream(noManifest),
                onMetadata = { _, _, _ -> },
                sink = FakeSink(),
                onImageRestored = { _, _ -> }
            )
            fail("InvalidFileException が投げられるべき")
        } catch (e: DataPackTransfer.InvalidFileException) {
            // 期待どおり
        }
    }

    @Test
    fun `1枚の保存に失敗しても残りは取り込まれる`() = runBlocking {
        val bytes = writePack(
            accounts = emptyList(),
            media = listOf(media("ok1", null), media("ng", null), media("ok2", null)),
            images = listOf(
                imageEntry("ok1", byteArrayOf(1)),
                imageEntry("ng", byteArrayOf(2)),
                imageEntry("ok2", byteArrayOf(3))
            )
        )

        val result = DataPackTransfer.read(
            input = ByteArrayInputStream(bytes),
            onMetadata = { _, _, _ -> },
            sink = object : DataPackTransfer.ImageSink {
                override suspend fun save(mediaKey: String, bytes: ByteArray): String {
                    if (mediaKey == "ng") error("ディスク書き込み失敗")
                    return "/path/$mediaKey.jpg"
                }
            },
            onImageRestored = { _, _ -> }
        )

        assertEquals(2, result.imagesRestored)
        assertEquals(1, result.imagesFailed)
    }
}
