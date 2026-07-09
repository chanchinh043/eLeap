// TtsAudioCache.kt
// Đặt tại: com/eleap/eleap/core/tts/pregen/TtsAudioCache.kt
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
// Singleton thủ công, KHÔNG dùng Hilt/DI — cùng phong cách với
// TtsReadingHistory, TtsForegroundReading, TtsVoiceSnapshot.
package com.eleap.eleap.core.tts.pregen

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

    // ── Thư mục gốc: filesDir/tts_cache ──────────────────────────────────────
    private fun rootDir(context: Context): File =
        File(context.applicationContext.filesDir, ROOT_DIR_NAME)

    // ── Thư mục của 1 (bài, giọng) cụ thể: filesDir/tts_cache/{readingId}/{sid} ─
    private fun voiceDir(context: Context, readingId: String, sid: Int): File =
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

    // ── Tên file: {loại}_{itemId}_{contentHash}.wav ─────────────────────────
    private fun fileName(type: TtsCacheItemType, itemId: String, contentHash: String): String =
        "${type.prefix}_${itemId}_$contentHash.wav"

    // ── Đường dẫn file ĐẦY ĐỦ theo đúng cấu trúc đã chốt — hàm DUY NHẤT build
    // path, mọi hàm khác trong object này đều gọi qua đây để tránh 2 nơi build
    // path khác nhau rồi lệch nhau. ─────────────────────────────────────────
    fun buildFilePath(
        context: Context,
        readingId: String,
        sid: Int,
        type: TtsCacheItemType,
        itemId: String,
        contentHash: String,
    ): File = File(voiceDir(context, readingId, sid), fileName(type, itemId, contentHash))

    // ── Kiểm tra ĐÃ có cache ĐÚNG hash chưa — false nếu: chưa từng generate,
    // HOẶC đã generate nhưng với nội dung CŨ (hash khác, tức đã lỗi thời do
    // nội dung bài vừa bị AI xử lý lại/sửa lại). TtsPregenWorker dùng hàm này
    // TRƯỚC MỖI ITEM để quyết định có cần generate hay bỏ qua. ──────────────
    fun hasCached(
        context: Context,
        readingId: String,
        sid: Int,
        type: TtsCacheItemType,
        itemId: String,
        contentHash: String,
    ): Boolean = buildFilePath(context, readingId, sid, type, itemId, contentHash).exists()

    // ── Lấy file cache nếu có ĐÚNG hash — dùng cho TtsPlaybackRouter khi cần
    // phát. Trả về null nếu chưa có/đã lỗi thời — caller (TtsPlaybackRouter)
    // tự quyết định fallback sang generate on-the-fly qua TtsManager. ───────
    fun getCachedFile(
        context: Context,
        readingId: String,
        sid: Int,
        type: TtsCacheItemType,
        itemId: String,
        contentHash: String,
    ): File? {
        val file = buildFilePath(context, readingId, sid, type, itemId, contentHash)
        return if (file.exists()) file else null
    }

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
    // cùng hash cũng chẳng có gì để xoá, nhưng viết rõ ràng cho chắc). ──────
    private fun deleteStaleFiles(dir: File, type: TtsCacheItemType, itemId: String, newContentHash: String) {
        val newFileName = fileName(type, itemId, newContentHash)
        val prefix = "${type.prefix}_${itemId}_"
        dir.listFiles()?.forEach { existing ->
            if (existing.name != newFileName &&
                existing.name.startsWith(prefix) &&
                existing.name.endsWith(".wav")
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