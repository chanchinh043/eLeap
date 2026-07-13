// TtsAudioCache.kt
// Đặt tại: com/eleap/eleap/core/tts/cache/TtsAudioCache.kt
//
// ⚠️ PHIÊN BẢN SAU KHI BỎ TỰ SINH AUDIO (KOKORO) — app KHÔNG còn tự generate
// audio on-device nữa, TOÀN BỘ audio đều được tải sẵn về từ nguồn ngoài (xem
// package core/tts/remote/ — TtsRemotePackDownloader.kt) rồi giải nén thẳng
// vào đúng thư mục mà voiceDir() bên dưới quy định. File này giờ CHỈ còn 2
// việc: (1) định nghĩa quy ước path/tên file dùng CHUNG giữa remote/ (nhánh
// ghi — giải nén zip vào đây) và TtsPlaybackRouter (nhánh đọc — tra cứu để
// phát), (2) cung cấp hàm tiện ích tính contentHash để phát hiện cache lỗi
// thời khi nội dung bài đổi.
//
// KHÔNG còn hàm ghi file nào ở đây nữa (không saveGenerated/writeWav...) —
// việc GHI file cache giờ hoàn toàn do TtsRemotePackDownloader.extractZip()
// đảm nhiệm (giải nén trực tiếp từ file .zip tải về, xem file đó).
//
// Lớp tiện ích THAO TÁC FILE THUẦN TUÝ — KHÔNG dùng database để index cache.
// Sự tồn tại của đúng file (đúng tên, đúng hash nội dung) TỰ NÓ là "index".
//
// Cấu trúc thư mục (tách riêng theo bài VÀ theo giọng — người dùng có thể
// chọn nhiều giọng khác nhau, xem TtsVoiceSnapshot.kt ở bước sau):
//   filesDir/tts_cache/{readingId}/{sid}/word_{wordId}_{contentHash}.ogg
//   filesDir/tts_cache/{readingId}/{sid}/sentence_{sentenceId}_{contentHash}.ogg
//   filesDir/tts_cache/{readingId}/{sid}/phrase_{phraseId}_{contentHash}.ogg
//
// contentHash: hash NGẮN của text_en tương ứng (8 ký tự đầu SHA-256) — dùng để
// tự phát hiện cache lỗi thời khi nội dung bài đổi (AI xử lý lại/sửa lại từ,
// câu, cụm từ) hoặc khi server build lại gói với nội dung khác. Không cần
// cryptographic mạnh vì chỉ để SO SÁNH THAY ĐỔI NỘI DUNG, không phải mục đích
// bảo mật — SHA-256 được chọn thay vì CRC32 chỉ vì đã có sẵn trong
// java.security, không cần thêm thư viện ngoài.
//
// Định dạng lưu: .ogg (Opus/Vorbis, tuỳ pipeline build gói phía server) — file
// tải nguyên vẹn từ Drive, app không đụng gì tới nội dung bên trong, chỉ đặt
// đúng chỗ theo quy ước tên đã thống nhất với server.
//
// Singleton thủ công, KHÔNG dùng Hilt/DI — cùng phong cách với các singleton
// khác trong core/tts/.
package com.eleap.eleap.core.tts.cache

import android.content.Context
import java.io.File
import java.security.MessageDigest

// ── Loại item được cache — dùng làm tiền tố tên file, cũng là tham số bắt
// buộc ở mọi hàm để tránh nhầm lẫn giữa 3 loại khi build đường dẫn. ──────────
enum class TtsCacheItemType(val prefix: String) {
    WORD("word"),
    SENTENCE("sentence"),
    PHRASE("phrase"),
}

object TtsAudioCache {

    private const val ROOT_DIR_NAME = "tts_cache"
    private const val AUDIO_EXTENSION = "ogg"

    // ── Thư mục gốc: filesDir/tts_cache ──────────────────────────────────────
    private fun rootDir(context: Context): File =
        File(context.applicationContext.filesDir, ROOT_DIR_NAME)

    // ── Thư mục của 1 (bài, giọng) cụ thể: filesDir/tts_cache/{readingId}/{sid} ─
    // Không `private` — package remote/ cần biết CHÍNH XÁC thư mục đích để
    // giải nén file vào đúng chỗ, đúng cấu trúc mà getCachedFile()/hasCached()
    // bên dưới sẽ tìm tới. Đây là hàm DUY NHẤT build đúng path này, tránh 2
    // nơi tự định nghĩa lại rồi lệch nhau nếu sau này đổi cấu trúc thư mục.
    fun voiceDir(context: Context, readingId: String, sid: Int): File =
        File(File(rootDir(context), readingId), sid.toString())

    // ── contentHash: 8 ký tự đầu SHA-256 của text — dùng để phát hiện nội
    // dung đã đổi (AI xử lý lại bài, sửa lại từ/câu/cụm, hoặc server build lại
    // gói với nội dung khác) mà không cần biết "phiên bản" nào, chỉ cần so
    // sánh hash với tên file hiện có trên đĩa. ───────────────────────────────
    fun contentHash(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        // Chuyển sang hex rồi cắt 8 ký tự đầu — đủ để tránh đụng độ trong
        // phạm vi số lượng item của 1 bài đọc (vài trăm từ/câu/cụm), không
        // cần toàn bộ 64 ký tự hex của SHA-256 đầy đủ.
        return digest.joinToString("") { "%02x".format(it) }.take(8)
    }

    // ── Tên file: {loại}_{itemId}_{contentHash}.ogg ─────────────────────────
    // Đây cũng chính là quy ước tên file mà server PHẢI đóng gói đúng bên
    // trong .zip (xem TtsRemotePackDownloader.extractZip()) — nếu tên file
    // trong zip không đúng format này, app sẽ không tra cứu ra được.
    private fun fileName(type: TtsCacheItemType, itemId: String, contentHash: String): String =
        "${type.prefix}_${itemId}_$contentHash.$AUDIO_EXTENSION"

    // ── Đường dẫn file ĐẦY ĐỦ theo đúng cấu trúc đã chốt — hàm DUY NHẤT build
    // path, mọi hàm khác trong object này đều gọi qua đây để tránh 2 nơi build
    // path khác nhau rồi lệch nhau. ──────────────────────────────────────────
    fun buildFilePath(
        context: Context,
        readingId: String,
        sid: Int,
        type: TtsCacheItemType,
        itemId: String,
        contentHash: String,
    ): File = File(voiceDir(context, readingId, sid), fileName(type, itemId, contentHash))

    // ── Kiểm tra ĐÃ có cache ĐÚNG hash chưa — false nếu: chưa từng tải về,
    // HOẶC đã có nhưng với nội dung CŨ (hash khác, tức đã lỗi thời do nội
    // dung bài vừa bị AI xử lý lại/sửa lại, hoặc server build lại gói mới). ──
    fun hasCached(
        context: Context,
        readingId: String,
        sid: Int,
        type: TtsCacheItemType,
        itemId: String,
        contentHash: String,
    ): Boolean = buildFilePath(context, readingId, sid, type, itemId, contentHash).exists()

    // ── Lấy file cache nếu có ĐÚNG hash — dùng cho TtsPlaybackRouter khi cần
    // phát. Trả về null nếu chưa có/đã lỗi thời — caller tự quyết định
    // fallback sang Android TTS. MediaPlayer phát .ogg trực tiếp được, không
    // cần xử lý gì thêm. ──────────────────────────────────────────────────
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
}