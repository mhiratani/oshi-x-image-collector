package com.hilamalu.oshixcollector.data.transfer

import com.hilamalu.oshixcollector.data.db.MediaAssetEntity
import com.hilamalu.oshixcollector.data.db.TargetAccountEntity
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json

/**
 * 収集済みデータ（追跡アカウント・画像メタデータ・画像本体）を1つのZIPにまとめる読み書き。
 *
 * クラウドバックアップを設定していないユーザーが機種変更・再インストール時にデータを持ち出すための
 * オフライン手段。認証情報は含まないため暗号化しない（Bearer Token等は暗号化された
 * [com.hilamalu.oshixcollector.data.settings.SettingsTransfer] 側に残る）。
 * ZIPなのでPCでも開け、一部が壊れても他のエントリを救出できる。
 *
 * AndroidのContextやRoomに依存させず[InputStream]/[OutputStream]とdata classだけを扱うことで、
 * 往復の正しさをJVM単体テストで検証できるようにしている。
 */
object DataPackTransfer {

    /** ZIPの先頭に置く目録。取り込み前の検証と空き容量チェックに使う。 */
    @kotlinx.serialization.Serializable
    data class Manifest(
        val format: String = FORMAT,
        val version: Int = VERSION,
        val exportedAt: Long = 0,
        val accountCount: Int = 0,
        val mediaCount: Int = 0,
        val imageCount: Int = 0,
        /** 画像本体の合計バイト数。取り込み先の空き容量と比較するために持つ。 */
        val imageBytesTotal: Long = 0,
    )

    /** 取り込み時に1画像ずつ渡ってくるコールバック。書き込み先の決定は呼び出し側に任せる。 */
    interface ImageSink {
        /** [mediaKey]の画像を[bytes]で保存し、保存先の絶対パスを返す。 */
        suspend fun save(mediaKey: String, bytes: ByteArray): String
    }

    class InvalidFileException : Exception("このアプリのデータファイルではありません")
    class UnsupportedVersionException(version: Int) :
        Exception("このデータファイル(version $version)は、このバージョンのアプリでは取り込めません")

    const val FORMAT = "oshi-x-collector-data"
    const val VERSION = 1

    private const val ENTRY_MANIFEST = "manifest.json"
    private const val ENTRY_ACCOUNTS = "accounts.json"
    private const val ENTRY_MEDIA = "media.json"
    private const val IMAGE_PREFIX = "images/"
    private const val IMAGE_SUFFIX = ".jpg"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * [out]へZIPを書き出す。エントリ順は manifest → accounts → media → images で固定する。
     * [ZipInputStream]は順次読みしかできず、取り込み側は画像を展開する前にmedia行をDBへ
     * 入れておく必要があるため、この順序が仕様の一部になっている。
     *
     * [images]は「[mediaKey]と、その画像バイト列を返す関数」の列。ファイル全体を先読みせず
     * 1枚ずつ取り出してストリームへ流すことで、数GB規模でもメモリに載せない。
     */
    suspend fun write(
        out: OutputStream,
        accounts: List<TargetAccountEntity>,
        media: List<MediaAssetEntity>,
        images: List<ImageEntry>,
        exportedAt: Long,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ) {
        val zip = ZipOutputStream(out.buffered())
        // JPEGは既に圧縮済みなので、再圧縮してもサイズはほぼ減らずCPUと時間だけ食う
        zip.setLevel(Deflater.NO_COMPRESSION)

        val manifest = Manifest(
            exportedAt = exportedAt,
            accountCount = accounts.size,
            mediaCount = media.size,
            imageCount = images.size,
            imageBytesTotal = images.sumOf { it.sizeBytes },
        )
        zip.writeText(ENTRY_MANIFEST, json.encodeToString(Manifest.serializer(), manifest))
        zip.writeText(ENTRY_ACCOUNTS, json.encodeToString(accountsSerializer, accounts))
        // localImagePath は端末ごとに異なる絶対パスなので、そのまま持ち出さず取り込み側で貼り直す
        zip.writeText(ENTRY_MEDIA, json.encodeToString(mediaSerializer, media.map { it.copy(localImagePath = null) }))

        images.forEachIndexed { index, entry ->
            zip.putNextEntry(ZipEntry("$IMAGE_PREFIX${entry.mediaKey}$IMAGE_SUFFIX"))
            entry.openStream().use { input -> input.copyTo(zip) }
            zip.closeEntry()
            onProgress(index + 1, images.size)
        }
        zip.finish()
        zip.flush()
    }

    /** 書き出し対象の画像1枚。バイト列は[openStream]が呼ばれた時に初めて読む。 */
    data class ImageEntry(
        val mediaKey: String,
        val sizeBytes: Long,
        val openStream: () -> InputStream,
    )

    /** 取り込み結果のサマリー。 */
    data class ReadResult(
        val manifest: Manifest,
        val accounts: List<TargetAccountEntity>,
        val media: List<MediaAssetEntity>,
        val imagesRestored: Int,
        val imagesFailed: Int,
    )

    /**
     * [input]のZIPを先頭から読み、accounts/mediaを[onMetadata]へ渡してからimagesを[sink]へ流す。
     * [onMetadata]でDBへ書き込んでおくことで、続く画像展開時のパス貼り直しが空振りしない。
     */
    suspend fun read(
        input: InputStream,
        onMetadata: suspend (Manifest, List<TargetAccountEntity>, List<MediaAssetEntity>) -> Unit,
        sink: ImageSink,
        onImageRestored: suspend (mediaKey: String, path: String) -> Unit,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): ReadResult {
        val zip = ZipInputStream(input.buffered())

        var manifest: Manifest? = null
        var accounts: List<TargetAccountEntity> = emptyList()
        var media: List<MediaAssetEntity> = emptyList()
        var metadataApplied = false
        var restored = 0
        var failed = 0

        while (true) {
            val entry = zip.nextEntry ?: break
            when {
                entry.name == ENTRY_MANIFEST -> {
                    manifest = try {
                        json.decodeFromString(Manifest.serializer(), zip.readBytes().decodeToString())
                    } catch (e: Exception) {
                        throw InvalidFileException()
                    }
                    if (manifest.format != FORMAT) throw InvalidFileException()
                    if (manifest.version > VERSION) throw UnsupportedVersionException(manifest.version)
                }

                entry.name == ENTRY_ACCOUNTS -> {
                    requireManifest(manifest)
                    accounts = json.decodeFromString(accountsSerializer, zip.readBytes().decodeToString())
                }

                entry.name == ENTRY_MEDIA -> {
                    requireManifest(manifest)
                    media = json.decodeFromString(mediaSerializer, zip.readBytes().decodeToString())
                }

                entry.name.startsWith(IMAGE_PREFIX) && entry.name.endsWith(IMAGE_SUFFIX) -> {
                    val current = requireManifest(manifest)
                    // 画像より前にmedia行をDBへ入れておかないと、パスの貼り直しが空振りする
                    if (!metadataApplied) {
                        onMetadata(current, accounts, media)
                        metadataApplied = true
                    }
                    val mediaKey = entry.name
                        .removePrefix(IMAGE_PREFIX)
                        .removeSuffix(IMAGE_SUFFIX)
                    try {
                        val path = sink.save(mediaKey, zip.readBytes())
                        onImageRestored(mediaKey, path)
                        restored++
                    } catch (e: Exception) {
                        failed++
                    }
                    onProgress(restored + failed, current.imageCount)
                }

                else -> Unit // 将来のバージョンが増やしたエントリは無視する
            }
            zip.closeEntry()
        }

        val finalManifest = requireManifest(manifest)
        // 画像が0枚のパックでもメタデータは反映する
        if (!metadataApplied) onMetadata(finalManifest, accounts, media)

        return ReadResult(finalManifest, accounts, media, restored, failed)
    }

    private fun requireManifest(manifest: Manifest?): Manifest =
        manifest ?: throw InvalidFileException()

    private fun ZipOutputStream.writeText(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray())
        closeEntry()
    }

    private val accountsSerializer =
        kotlinx.serialization.builtins.ListSerializer(TargetAccountEntity.serializer())
    private val mediaSerializer =
        kotlinx.serialization.builtins.ListSerializer(MediaAssetEntity.serializer())
}
