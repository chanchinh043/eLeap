// TtsAudioCache.kt
// Đặt tại: com/eleap/eleap/core/tts/cache/TtsAudioCache.kt
//
// ⚠️ CHUYỂN VỊ TRÍ: trước đây nằm ở core/tts/pregen/ — giờ chuyển sang
// core/tts/cache/ vì đây là quy ước LƯU TRỮ CHUNG (path + tên file + định
// dạng WAV), không gắn riêng với việc TỰ SINH audio (pregen/). Có 2 nguồn
// cùng ghi vào đúng cache này: pregen/ (tự sinh bằng Kokoro) và remote/
// (tải sẵn từ xa) — cả 2 đều không cần biết nguồn còn lại tồn tại, chỉ cần
// tuân theo đúng quy ước path/tên file định nghĩa trong file này.
//
// Lớp tiện ích THAO TÁC FILE THUẦN TUÝ — KHÔNG dùng database để index cache.
// Sự tồn tại của đúng file (đúng tên, đúng hash nội dung) TỰ NÓ là "index":
// nếu app bị kill giữa chừng lúc đang generate rồi mở lại, TtsPregenWorker chỉ
// cần quét lại xem file nào đã tồn tại đúng tên để biết đã làm tới đâu — đây
// chính là cơ chế "resume" tự nhiên đã chốt ở bước thiết kế tổng thể, không
// cần lưu tiến độ riêng ở bất kỳ đâu khác.
//
// Cấu trúc thư mục (tách riêng theo bài VÀ theo giọng — xem lý do ở
// KokoroTtsEngine.kt/TtsVoiceSnapshot.kt: đổi giọng giữa chừng không xoá cache
// giọng cũ vì đã tách thư mục theo sid):
//   filesDir/tts_cache/{readingId}/{sid}/word_{wordId}_{contentHash}.wav
//   filesDir/tts_cache/{readingId}/{sid}/sentence_{sentenceId}_{contentHash}.wav
//   filesDir/tts_cache/{readingId}/{sid}/phrase_{phraseId}_{contentHash}.wav
//
// contentHash: hash NGẮN của text_en tương ứng (8 ký tự đầu SHA-256) — dùng để
// tự phát hiện cache lỗi thời khi nội dung bài đổi (AI xử lý lại/sửa lại từ,
// câu, cụm từ). Không cần cryptographic mạnh vì chỉ để SO SÁNH THAY ĐỔI NỘI
// DUNG, không phải mục đích bảo mật — SHA-256 được chọn thay vì CRC32 chỉ vì
// đã có sẵn trong java.security, không cần thêm thư viện ngoài.
//
// ⚠️ Định dạng lưu: WAV PCM 16-bit mono — KHÔNG lưu thẳng FloatArray thô, vì
// cần 1 định dạng file chuẩn, phát lại được bằng bất kỳ công cụ nào (kể cả
// ngoài app, lúc debug) mà không cần biết trước sampleRate/số kênh bằng cách
// nào khác ngoài đọc header. KokoroTtsEngine.generate() trả về
// (samples: FloatArray, sampleRate: Int) — lớp này tự đóng gói thành WAV
// 16-bit PCM (convert Float [-1,1] → Short) thay vì giữ nguyên Float PCM, để
// tương thích rộng rãi hơn (nhiều trình phát/thư viện không đọc được WAV
// float 32-bit đúng cách).
//
// ⚠️ MỚI: TRA CỨU (hasCached/getCachedFile) giờ chấp nhận CẢ 2 định dạng
// đuôi file — ".wav" (tự sinh on-device qua pregen/, luôn do chính
// saveGenerated() ở file này ghi ra) VÀ ".ogg" (tải sẵn từ Drive qua
// remote/, xem TtsRemotePackDownloader.kt/TtsGoogleDriveSource.kt — zip
// giải nén thẳng vào voiceDir() với tên file ĐÃ đúng quy ước
// "{type}_{itemId}_{hash}.ogg" sẵn từ lúc đóng gói, không đi qua
// saveGenerated()). 2 nguồn này KHÔNG BAO GIỜ cùng tồn tại cho ĐÚNG 1
// (readingId, sid, type, itemId, hash) tại 1 thời điểm trong thực tế (hash
// đã bao gồm nội dung text, nếu remote có sẵn đúng hash thì pregen/ sẽ tự
// thấy hasCached()=true và bỏ qua generate, không tạo ra file .wav trùng
// lặp) — nhưng hàm tra cứu vẫn thử CẢ HAI đuôi để không phụ thuộc thứ tự
// tải về hay tự sinh trước, đảm bảo luôn tìm thấy cache nếu có ở BẤT KỲ
// định dạng nào. saveGenerated() (nhánh GHI, chỉ dùng bởi pregen/) vẫn LUÔN
// ghi ra ".wav" — không đổi, vì đây là audio tự sinh trực tiếp từ
// FloatArray, ghi WAV là rẻ nhất, không cần encoder nào thêm.
//
// Singleton thủ công, KHÔNG dùng Hilt/DI — cùng phong cách với
// TtsReadingHistory, TtsForegroundReading, TtsVoiceSnapshot (các file này
// vẫn ở core/tts/pregen/, không bị ảnh hưởng bởi việc chuyển file này).
package com.eleap.eleap.core.tts.cache

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

// ── Loại item được cache — dùng làm tiền tố tên file, cũng là tham số bắt
// buộc ở mọi hàm để tránh nhầm lẫn giữa 3 loại khi build đường dẫn. ──────────
enum class TtsCacheItemType(val prefix: String) {
    WORD("word"),
    SENTENCE("sentence"),
    PHRASE("phrase"),
}

object TtsAudioCache {

    private const val TAG = "TtsAudioCache"
    private const val ROOT_DIR_NAME = "tts_cache"

    // ── Đuôi file cache được CHẤP NHẬN khi TRA CỨU (hasCached/getCachedFile)
    // — THỨ TỰ ưu tiên "ogg" TRƯỚC "wav" (đã đổi, cố ý): về nguyên tắc 2
    // nguồn không cùng tồn tại cho đúng 1 hash (remote/ chỉ tải hash CHƯA có
    // cache — xem TtsPregenWorker.ensureRemotePackSynced() gọi TRƯỚC khi
    // generate on-device), nhưng trên thực tế vẫn có thể xảy ra "lẫn lộn"
    // tạm thời (vd .wav đã generate on-device TRƯỚC KHI gói .ogg cùng hash
    // được tải về sau đó — TtsRemotePackDownloader.extractZip() giờ tự dọn
    // .wav cũ khi giải nén .ogg mới, xem file đó, nhưng vẫn giữ thứ tự ưu
    // tiên này ở đây làm lớp phòng thủ thứ 2). Ưu tiên "ogg" vì đây là audio
    // ĐÃ ĐƯỢC KIỂM CÂM SẴN ở pipeline build gói (xem TtsCacheAuditor.kt —
    // audit() chỉ kiểm .wav, coi .ogg là đã qua kiểm từ trước), đáng tin hơn
    // 1 file .wav tự sinh on-device có thể chưa qua audit lần nào.
    // saveGenerated() (nhánh GHI) KHÔNG dùng danh sách này — luôn ghi cứng
    // ".wav" (xem WAV_EXTENSION bên dưới).
    private val CACHE_EXTENSIONS = listOf("ogg", "wav")
    private const val WAV_EXTENSION = "wav"

    // ── Thư mục gốc: filesDir/tts_cache ──────────────────────────────────────
    private fun rootDir(context: Context): File =
        File(context.applicationContext.filesDir, ROOT_DIR_NAME)

    // ── Thư mục của 1 (bài, giọng) cụ thể: filesDir/tts_cache/{readingId}/{sid} ─
    // ⚠️ MỚI: bỏ `private` — package remote/ (tải gói giọng từ xa) cần biết
    // CHÍNH XÁC thư mục đích để giải nén file .wav vào đúng chỗ, đúng cấu
    // trúc mà getCachedFile()/hasCached() bên dưới sẽ tìm tới. Không cho
    // remote/ tự build lại đường dẫn này (vd tự nối chuỗi "tts_cache/$readingId/$sid")
    // để tránh 2 nơi định nghĩa path rồi lệch nhau nếu sau này đổi cấu trúc
    // thư mục — đây vẫn là hàm DUY NHẤT build đúng path này.
    fun voiceDir(context: Context, readingId: String, sid: Int): File =
        File(File(rootDir(context), readingId), sid.toString())

    // ── contentHash: 8 ký tự đầu SHA-256 của text — dùng để phát hiện nội
    // dung đã đổi (AI xử lý lại bài, sửa lại từ/câu/cụm) mà không cần biết
    // "phiên bản" nào, chỉ cần so sánh hash với tên file hiện có trên đĩa. ───
    fun contentHash(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        // Chuyển sang hex rồi cắt 8 ký tự đầu — đủ để tránh đụng độ trong
        // phạm vi số lượng item của 1 bài đọc (vài trăm từ/câu/cụm), không
        // cần toàn bộ 64 ký tự hex của SHA-256 đầy đủ.
        return digest.joinToString("") { "%02x".format(it) }.take(8)
    }

    // ── Tên file: {loại}_{itemId}_{contentHash}.{extension} ─────────────────
    // extension mặc định "wav" — GIỮ NGUYÊN hành vi cũ cho mọi nơi gọi chưa
    // truyền tham số này (buildFilePath() dùng bởi saveGenerated()).
    private fun fileName(
        type: TtsCacheItemType,
        itemId: String,
        contentHash: String,
        extension: String = WAV_EXTENSION,
    ): String = "${type.prefix}_${itemId}_$contentHash.$extension"

    // ── Đường dẫn file ĐẦY ĐỦ theo đúng cấu trúc đã chốt — hàm DUY NHẤT build
    // path, mọi hàm khác trong object này đều gọi qua đây để tránh 2 nơi build
    // path khác nhau rồi lệch nhau. extension mặc định "wav" — chữ ký cũ vẫn
    // hoạt động y hệt trước đây (dùng cho saveGenerated(), luôn ghi .wav). ──
    fun buildFilePath(
        context: Context,
        readingId: String,
        sid: Int,
        type: TtsCacheItemType,
        itemId: String,
        contentHash: String,
        extension: String = WAV_EXTENSION,
    ): File = File(voiceDir(context, readingId, sid), fileName(type, itemId, contentHash, extension))

    // ── Tìm file cache ĐÃ TỒN TẠI, thử LẦN LƯỢT từng đuôi trong
    // CACHE_EXTENSIONS — dùng chung cho cả hasCached()/getCachedFile() để
    // không viết lặp lại vòng lặp này 2 nơi. Trả về file ĐẦU TIÊN tồn tại
    // (thứ tự CACHE_EXTENSIONS), hoặc null nếu không đuôi nào có. ───────────
    private fun findExistingCacheFile(
        context: Context,
        readingId: String,
        sid: Int,
        type: TtsCacheItemType,
        itemId: String,
        contentHash: String,
    ): File? {
        for (extension in CACHE_EXTENSIONS) {
            val file = buildFilePath(context, readingId, sid, type, itemId, contentHash, extension)
            if (file.exists()) return file
        }
        return null
    }

    // ── Kiểm tra ĐÃ có cache ĐÚNG hash chưa (BẤT KỂ định dạng .wav hay .ogg)
    // — false nếu: chưa từng generate/tải về, HOẶC đã có nhưng với nội dung
    // CŨ (hash khác, tức đã lỗi thời do nội dung bài vừa bị AI xử lý lại/sửa
    // lại). TtsPregenWorker dùng hàm này TRƯỚC MỖI ITEM để quyết định có cần
    // generate hay bỏ qua — nhờ tra cứu cả 2 đuôi, nếu remote/ đã tải sẵn
    // đúng hash này (.ogg) thì pregen/ sẽ tự bỏ qua, KHÔNG generate trùng
    // lặp bằng .wav nữa. ─────────────────────────────────────────────────
    fun hasCached(
        context: Context,
        readingId: String,
        sid: Int,
        type: TtsCacheItemType,
        itemId: String,
        contentHash: String,
    ): Boolean = findExistingCacheFile(context, readingId, sid, type, itemId, contentHash) != null

    // ── Lấy file cache nếu có ĐÚNG hash (BẤT KỂ định dạng) — dùng cho
    // TtsPlaybackRouter khi cần phát. Trả về null nếu chưa có/đã lỗi thời —
    // caller (TtsPlaybackRouter) tự quyết định fallback sang generate
    // on-the-fly qua TtsManager. MediaPlayer phát được cả .wav lẫn .ogg mà
    // không cần biết trước đuôi file là gì (setDataSource() tự nhận diện). ──
    fun getCachedFile(
        context: Context,
        readingId: String,
        sid: Int,
        type: TtsCacheItemType,
        itemId: String,
        contentHash: String,
    ): File? = findExistingCacheFile(context, readingId, sid, type, itemId, contentHash)

    // ── Lưu audio vừa generate xong vào cache — TỰ ĐỘNG:
    //   1. Tính contentHash NGAY TRONG hàm này từ `text` — caller chỉ cần
    //      truyền đúng nội dung gốc đã dùng để generate, không phải tự gọi
    //      contentHash() rồi truyền tay (giảm khả năng truyền lệch/quên gọi).
    //   2. Tạo thư mục cha nếu chưa có — nếu mkdirs() THẤT BẠI (hết dung
    //      lượng, permission bị thu hồi...) → trả về null NGAY, không cố ghi
    //      file vào thư mục không tồn tại (sẽ ném exception mù mờ hơn).
    //   3. Xoá SẠCH mọi file CŨ của ĐÚNG item này (cùng loại + cùng itemId)
    //      nhưng KHÁC hash — đây là các file lỗi thời do nội dung đã đổi,
    //      xoá đi để không tồn đọng rác vô thời hạn trong bộ nhớ máy (mỗi
    //      lần AI xử lý lại 1 bài sẽ để lại rác nếu không dọn).
    //   4. Ghi file WAV mới (PCM 16-bit mono) từ FloatArray samples.
    // Trả về File vừa ghi nếu thành công, null nếu có lỗi (I/O, hết dung
    // lượng, mkdirs thất bại...). TtsPregenWorker KHÔNG nên throw nếu hàm
    // này trả về null — chỉ log và thử lại ở lượt quét kế tiếp (không có gì
    // để retry ngay, đơn giản coi như item đó chưa xong).
    fun saveGenerated(
        context: Context,
        readingId: String,
        sid: Int,
        type: TtsCacheItemType,
        itemId: String,
        text: String,
        samples: FloatArray,
        sampleRate: Int,
    ): File? {
        val contentHash = contentHash(text)
        return try {
            val dir = voiceDir(context, readingId, sid)
            if (!dir.exists() && !dir.mkdirs()) {
                Log.e(TAG, "saveGenerated: mkdirs() thất bại cho $dir, không thể lưu item=$itemId")
                return null
            }

            deleteStaleFiles(dir, type, itemId, newContentHash = contentHash)

            val targetFile = File(dir, fileName(type, itemId, contentHash))
            writeWav(targetFile, samples, sampleRate)
            Log.d(
                TAG,
                "saveGenerated: đã lưu '${targetFile.name}' " +
                        "(reading=$readingId, sid=$sid, samples=${samples.size}, sampleRate=$sampleRate) " +
                        "path=${targetFile.absolutePath}"
            )
            targetFile
        } catch (e: Exception) {
            Log.e(TAG, "saveGenerated: lỗi khi lưu cache cho item=$itemId (reading=$readingId, sid=$sid)", e)
            null
        }
    }

    // ── Dọn file CŨ của cùng item (loại + itemId giống nhau) nhưng hash KHÁC
    // — đây là bản ghi ứng với nội dung TRƯỚC khi bị sửa. So khớp theo tiền
    // tố "{loại}_{itemId}_" rồi loại trừ đúng tên file MỚI sắp ghi, để không
    // tự xoá nhầm file vừa định tạo (dù trên thực tế 2 tên trùng nhau thì
    // cùng hash cũng chẳng có gì để xoá, nhưng viết rõ ràng cho chắc).
    //
    // ⚠️ MỚI: kiểm tra đuôi file theo CACHE_EXTENSIONS (wav VÀ ogg) thay vì
    // chỉ ".wav" như trước — dù hàm này hiện chỉ được gọi từ saveGenerated()
    // (luôn ghi .wav mới), việc dọn dẹp vẫn nên xoá được cả file .ogg cũ nếu
    // có (vd trường hợp hiếm: remote/ từng tải về .ogg cho hash cũ, sau đó
    // nội dung bài đổi khiến pregen/ phải tự generate lại bằng .wav cho hash
    // mới — file .ogg hash cũ khi đó là rác, nên dọn theo cùng logic này
    // luôn cho nhất quán, không để sót theo định dạng). ─────────────────────
    private fun deleteStaleFiles(dir: File, type: TtsCacheItemType, itemId: String, newContentHash: String) {
        val newFileName = fileName(type, itemId, newContentHash)
        val prefix = "${type.prefix}_${itemId}_"
        dir.listFiles()?.forEach { existing ->
            val hasKnownExtension = CACHE_EXTENSIONS.any { ext -> existing.name.endsWith(".$ext") }
            if (existing.name != newFileName &&
                existing.name.startsWith(prefix) &&
                hasKnownExtension
            ) {
                val deleted = existing.delete()
                Log.d(
                    TAG,
                    "deleteStaleFiles: dọn cache cũ '${existing.name}' (đã lỗi thời) → deleted=$deleted"
                )
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ── Ghi file WAV PCM 16-bit mono từ FloatArray [-1.0, 1.0] ───────────────
    // Không dùng thư viện ngoài — tự viết header 44 byte theo chuẩn WAV/RIFF,
    // đủ dùng cho mục đích phát lại nội bộ qua MediaPlayer/AudioTrack hoặc bất
    // kỳ trình phát chuẩn nào khác (kể cả khi kéo file ra ngoài để debug).
    // Tách thành 3 hàm nhỏ (writeWav/writeWavHeader/floatToPcm16) để dễ đọc
    // và dễ test riêng từng phần (vd test floatToPcm16 với input biên
    // -1f/1f/ngoài khoảng mà không cần dựng cả 1 File thật).
    // ─────────────────────────────────────────────────────────────────────────
    private fun writeWav(file: File, samples: FloatArray, sampleRate: Int) {
        val numChannels = 1
        val bitsPerSample = 16
        val pcmData = floatToPcm16(samples)

        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0) // đảm bảo ghi đè sạch nếu file cũ (hiếm khi xảy ra vì tên đã kèm hash)
            writeWavHeader(raf, dataSize = pcmData.size, sampleRate = sampleRate, numChannels = numChannels, bitsPerSample = bitsPerSample)
            raf.write(pcmData)
        }
    }

    // ── Ghi 44-byte header chuẩn RIFF/WAVE — RandomAccessFile đã ở đúng vị
    // trí đầu file (offset 0) trước khi hàm này được gọi. ───────────────────
    private fun writeWavHeader(
        raf: RandomAccessFile,
        dataSize: Int,
        sampleRate: Int,
        numChannels: Int,
        bitsPerSample: Int,
    ) {
        val byteRate = sampleRate * numChannels * bitsPerSample / 8
        val blockAlign = numChannels * bitsPerSample / 8
        val riffChunkSize = 36 + dataSize

        // ── RIFF header ───────────────────────────────────────────────────
        raf.writeAsciiBytes("RIFF")
        raf.writeIntLE(riffChunkSize)
        raf.writeAsciiBytes("WAVE")

        // ── fmt chunk ─────────────────────────────────────────────────────
        raf.writeAsciiBytes("fmt ")
        raf.writeIntLE(16) // fmt chunk size (16 cho PCM)
        raf.writeShortLE(1) // AudioFormat = 1 (PCM thường, không nén)
        raf.writeShortLE(numChannels.toShort())
        raf.writeIntLE(sampleRate)
        raf.writeIntLE(byteRate)
        raf.writeShortLE(blockAlign.toShort())
        raf.writeShortLE(bitsPerSample.toShort())

        // ── data chunk (header) — phần data thật ghi ngay sau lời gọi này ──
        raf.writeAsciiBytes("data")
        raf.writeIntLE(dataSize)
    }

    // ── Convert Float [-1,1] → PCM 16-bit little-endian — clamp để tránh
    // tràn số nếu Kokoro trả về giá trị hơi vượt [-1,1]. ────────────────────
    private fun floatToPcm16(samples: FloatArray): ByteArray {
        val buffer = ByteArray(samples.size * 2)
        var offset = 0
        for (sampleValue in samples) {
            val clamped = sampleValue.coerceIn(-1f, 1f)
            val shortValue = (clamped * Short.MAX_VALUE).toInt().toShort()
            // Little-endian: byte thấp trước
            buffer[offset] = (shortValue.toInt() and 0xFF).toByte()
            buffer[offset + 1] = ((shortValue.toInt() shr 8) and 0xFF).toByte()
            offset += 2
        }
        return buffer
    }

    private fun RandomAccessFile.writeAsciiBytes(text: String) {
        write(text.toByteArray(Charsets.US_ASCII))
    }

    private fun RandomAccessFile.writeIntLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }

    private fun RandomAccessFile.writeShortLE(value: Short) {
        val intValue = value.toInt()
        write(intValue and 0xFF)
        write((intValue shr 8) and 0xFF)
    }
}