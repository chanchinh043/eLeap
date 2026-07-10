// TtsCacheAuditor.kt
// Đặt tại: com/eleap/eleap/core/tts/cache/TtsCacheAuditor.kt
//
// ⚠️ CHUYỂN VỊ TRÍ: trước đây nằm ở core/tts/pregen/ — giờ chuyển sang
// core/tts/cache/ cùng với TtsAudioCache.kt, vì việc audit file .wav trên
// đĩa (đọc lại, kiểm tra câm) là thao tác trên CHÍNH cache, không gắn riêng
// với việc tự sinh audio. TtsPregenWorker (ở pregen/) vẫn là nơi DUY NHẤT
// gọi audit() để quyết định có cần regenerate hay không — file này chỉ đổi
// package, không đổi logic hay người gọi.
//
// ── VÌ SAO CẦN FILE NÀY (khác với check biên độ đã có trong KokoroTtsEngine) ─
// KokoroTtsEngine.logAmplitudeCheck() chỉ bắt được lỗi NGAY TẠI THỜI ĐIỂM
// generate() vừa chạy xong (in-memory, trên FloatArray samples còn trong
// RAM) — nó KHÔNG bao giờ tự động phát hiện lại các file .wav đã nằm sẵn
// trên đĩa TỪ TRƯỚC (vd cache tạo ra trước khi thêm check biên độ, hoặc lỡ
// bị ghi hỏng giữa chừng do I/O). TtsAudioCache.hasCached() chỉ kiểm tra
// FILE CÓ TỒN TẠI hay không (đúng tên, đúng hash) — không hề đọc NỘI DUNG
// bên trong, nên 1 file câm vẫn được coi là "đã cache xong", sẽ KHÔNG bao
// giờ tự sinh lại (vì content hash không đổi).
//
// TtsCacheAuditor bịt lỗ hổng đó: đọc lại phần "data" của file .wav đã có
// trên đĩa, tính biên độ tối đa, dùng CHUNG ngưỡng với KokoroTtsEngine
// (0.01f trên thang [-1,1]) để xác định "câm".
//
// KHÔNG có trạng thái "bỏ cuộc vĩnh viễn" nào cả — mọi file câm đều được đọc
// lại và thử regenerate ở MỌI lượt audit (sau mỗi câu xử lý xong, VÀ mỗi lần
// mở lại bài đọc đó), bất kể đã thất bại bao nhiêu lần ở các lượt trước.
//
// ⚠️ MỚI (hỗ trợ cache đa định dạng, xem TtsAudioCache.kt): audit() CHỈ áp
// dụng cho file .wav tự sinh on-device qua pregen/ — file .ogg tải sẵn từ
// Drive qua remote/ (TtsRemotePackDownloader.kt) KHÔNG được audit lại ở
// đây, vì coi như đã kiểm câm sẵn ở pipeline Python lúc build gói (cùng
// ngưỡng SILENCE_AMPLITUDE_THRESHOLD, xem step1_generate_kokoro_audio.py),
// và vì readMaxAmplitude() bên dưới đọc PCM16 thô ở offset cố định — chỉ
// đúng với WAV, không áp dụng được cho dữ liệu nén Opus.
//
// ⚠️ SỬA (mở rộng từ "cặp 2 giọng" → "nhóm 3 giọng", xem TtsVoicePairing.kt):
// trước đây quy trình retry có 2 tầng KHÔNG đối xứng (5 lần giọng gốc, rồi
// 3 lần giọng cặp) — dùng 2 hằng số riêng MAX_ATTEMPTS_PRIMARY_SID/
// MAX_ATTEMPTS_PAIRED_SID. Giờ quy trình retry ĐỐI XỨNG hoàn toàn giữa mọi
// giọng trong chuỗi fallback (giọng hiện tại + tối đa 2 giọng còn lại cùng
// nhóm, xem TtsVoicePairing.fallbackChainOf()) — mỗi giọng đều thử tối đa
// CÙNG 1 số lần, gộp lại thành 1 hằng số duy nhất MAX_ATTEMPTS_PER_VOICE.
// Quy trình đầy đủ (do TtsPregenWorker thực hiện, xem
// TtsPregenWorker.repairSilentItem()):
//   Với mỗi sid trong [sidHiệnTại] + TtsVoicePairing.fallbackChainOf(sidHiệnTại):
//     thử lại tối đa MAX_ATTEMPTS_PER_VOICE lần bằng ĐÚNG sid đó, kiểm tra
//     câm/hết câm sau MỖI lần, dừng ngay khi hết câm.
// Hết toàn bộ chuỗi (tối đa 3 giọng × 3 lần = 9 lần) mà vẫn câm → KHÔNG
// đánh dấu gì, chỉ log, để lượt audit kế tiếp (câu sau, hoặc lần mở bài
// sau) tự phát hiện và thử lại từ đầu.
//
// ── TỐI ƯU: audited-session cache ───────────────────────────────────────
// Để không phải đọc lại HÀNG TRĂM file .wav mỗi lần mở lại đúng 1 bài trong
// CÙNG 1 phiên chạy app (vd người dùng ra vào lại bài đó nhiều lần),
// TtsCacheAuditor tự nhớ trong RAM (object singleton, sống theo phiên
// process) các (readingId, sid) đã audit "sạch" (không có file câm nào chưa
// xử lý xong) — audit sau chỉ CẦN chạy lại nếu app khởi động lại process
// mới (RAM reset tự nhiên) hoặc sid đổi (key đã bao gồm sid). Tối ưu này
// KHÔNG mâu thuẫn với việc không có trạng thái "bỏ cuộc vĩnh viễn" ở trên —
// nó chỉ bỏ qua đọc lại khi lượt audit TRƯỚC ĐÓ (trong cùng phiên) không
// tìm thấy bất kỳ file câm nào, hoàn toàn khác với việc "bỏ cuộc" với 1
// item cụ thể đã biết là lỗi.
//
// Singleton thủ công, KHÔNG dùng Hilt/DI — cùng phong cách với TtsAudioCache
// (cùng package cache/) và các singleton khác trong core/tts/pregen/.
package com.eleap.eleap.core.tts.cache

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile

private const val TAG = "TtsCacheAuditor"

// ── Mô tả 1 item bất kỳ (từ/câu/cụm) cần audit — caller (TtsPregenWorker)
// tự build danh sách này từ dữ liệu đã đọc sẵn (TtsReadingContentReader),
// không cần TtsCacheAuditor tự đi query DB. ─────────────────────────────
data class TtsAuditItem(
    val type: TtsCacheItemType,
    val itemId: String,
    val text: String,
)

// ── Kết quả: 1 item ĐÃ CÓ cache nhưng nội dung bị câm ────────────────────
data class TtsSilentItem(
    val item: TtsAuditItem,
    val file: File,
    val hash: String,
    val maxAmplitude: Float,
)

object TtsCacheAuditor {

    // Cùng ngưỡng với KokoroTtsEngine.logAmplitudeCheck() — biên độ tối đa
    // dưới mức này coi như im lặng thật sự, không phải giọng nói khẽ.
    const val SILENCE_AMPLITUDE_THRESHOLD = 0.01f

    // ⚠️ SỬA: thay 2 hằng số cũ (MAX_ATTEMPTS_PRIMARY_SID=5,
    // MAX_ATTEMPTS_PAIRED_SID=3) bằng 1 hằng số DUY NHẤT — quy trình retry
    // giờ đối xứng: MỌI giọng trong chuỗi fallback (giọng hiện tại + các
    // giọng cùng nhóm) đều thử tối đa CÙNG số lần này trước khi chuyển
    // sang giọng kế tiếp trong chuỗi. Xem TtsVoicePairing.fallbackChainOf().
    const val MAX_ATTEMPTS_PER_VOICE = 3

    // WAV do TtsAudioCache ghi luôn có header cố định 44 byte (RIFF/WAVE,
    // PCM 16-bit mono) — xem TtsAudioCache.writeWavHeader(). An toàn giả
    // định offset này vì CHỈ có TtsAudioCache là nơi ghi các file trong
    // tts_cache/, không có nguồn nào khác tạo ra file .wav ở đây.
    private const val WAV_HEADER_SIZE = 44

    // (readingId, sid) đã audit sạch trong phiên chạy hiện tại — xem ghi
    // chú "audited-session cache" ở đầu file.
    private val auditedCleanThisSession = mutableSetOf<String>()

    private fun sessionKey(readingId: String, sid: Int) = "$readingId:$sid"

    // ── Điểm gọi CHÍNH — audit toàn bộ danh sách items của 1 bài, trả về
    // danh sách item bị câm CẦN xử lý (đã lọc bỏ item chưa từng cache — vì
    // không có gì để audit). KHÔNG có item nào bị bỏ qua vĩnh viễn — mọi
    // file đã cache đều được đọc lại mỗi lần gọi audit() (trừ tối ưu
    // audited-session ở trên). ──────────────────────────────────────────
    fun audit(
        context: Context,
        readingId: String,
        sid: Int,
        items: List<TtsAuditItem>,
    ): List<TtsSilentItem> {
        val key = sessionKey(readingId, sid)
        if (key in auditedCleanThisSession) {
            Log.d(TAG, "audit: reading=$readingId sid=$sid đã audit sạch trong phiên này, bỏ qua")
            return emptyList()
        }

        Log.d(TAG, "audit: bắt đầu quét ${items.size} item cho reading=$readingId sid=$sid")
        val startTime = System.currentTimeMillis()

        val silentItems = mutableListOf<TtsSilentItem>()
        var checkedCount = 0

        for (item in items) {
            val hash = TtsAudioCache.contentHash(item.text)
            // ⚠️ MỚI: truyền TƯỜNG MINH extension="wav" — TtsCacheAuditor CHỈ
            // audit file tự sinh on-device (.wav qua pregen/), KHÔNG audit
            // file tải sẵn từ Drive (.ogg qua remote/, xem
            // TtsRemotePackDownloader.kt/TtsGoogleDriveSource.kt). Lý do:
            // (1) audio .ogg đã được kiểm câm SẴN ở pipeline Python lúc build
            // gói (xem step1_generate_kokoro_audio.py — generate_with_fallback()
            // dùng cùng ngưỡng SILENCE_AMPLITUDE_THRESHOLD), coi như đã qua
            // audit trước khi đóng gói; (2) readMaxAmplitude() bên dưới đọc
            // thẳng byte PCM16 ở offset cố định 44 — CHỈ đúng với WAV, sẽ đọc
            // sai hoàn toàn (hoặc lỗi) nếu áp dụng cho dữ liệu nén Opus.
            // Nếu item chỉ có cache dạng .ogg (chưa từng tự sinh .wav) —
            // buildFilePath() trả về đường dẫn .wav CHƯA TỒN TẠI, nhánh
            // "!file.exists()" bên dưới tự bỏ qua đúng như mong muốn.
            val file = TtsAudioCache.buildFilePath(context, readingId, sid, item.type, item.itemId, hash, extension = "wav")

            if (!file.exists()) {
                // Chưa từng generate on-device (.wav), HOẶC đã có sẵn dạng
                // .ogg tải từ remote (được coi là đã kiểm câm từ trước, xem
                // ghi chú ở trên) — cả 2 trường hợp đều không có gì để audit
                // ở đây, vòng xử lý bình thường của Worker sẽ tự lo (generate
                // nếu thiếu, hoặc dùng thẳng .ogg nếu đã có).
                continue
            }

            checkedCount++
            val amplitude = readMaxAmplitude(file)
            if (amplitude == null) {
                // Đọc file lỗi (hỏng, thiếu quyền...) — log cảnh báo nhưng
                // KHÔNG coi là câm (không đủ căn cứ), bỏ qua, để lần audit
                // sau thử lại.
                Log.w(TAG, "audit: lỗi đọc file '${file.name}' (path=${file.absolutePath}), bỏ qua lượt này")
                continue
            }

            if (amplitude < SILENCE_AMPLITUDE_THRESHOLD) {
                Log.w(
                    TAG,
                    "audit: PHÁT HIỆN FILE CÂM type=${item.type.prefix} id=${item.itemId} " +
                            "text=\"${item.text}\" biên độ tối đa=$amplitude path=${file.absolutePath}"
                )
                silentItems.add(TtsSilentItem(item = item, file = file, hash = hash, maxAmplitude = amplitude))
            }
        }

        val elapsed = System.currentTimeMillis() - startTime
        Log.d(
            TAG,
            "audit: xong reading=$readingId sid=$sid — đã đọc $checkedCount file cache " +
                    "(bỏ qua ${items.size - checkedCount} item chưa cache), " +
                    "phát hiện ${silentItems.size} file câm, mất ${elapsed}ms"
        )

        if (silentItems.isEmpty()) {
            auditedCleanThisSession.add(key)
        }

        return silentItems
    }

    // ── Đọc phần "data" của file WAV (bỏ qua 44 byte header), tính biên độ
    // tối đa trên thang [-1, 1] — CÙNG công thức với logAmplitudeCheck() ở
    // KokoroTtsEngine (chỉ khác nguồn dữ liệu: đọc từ byte PCM16 trên đĩa
    // thay vì từ FloatArray samples trong RAM). Trả về null nếu đọc lỗi. ──
    private fun readMaxAmplitude(file: File): Float? {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val dataSize = (raf.length() - WAV_HEADER_SIZE).toInt()
                if (dataSize <= 0) return 0f // file rỗng/hỏng header — coi như câm

                raf.seek(WAV_HEADER_SIZE.toLong())
                val bytes = ByteArray(dataSize)
                raf.readFully(bytes)

                var maxAbs = 0
                var i = 0
                while (i + 1 < bytes.size) {
                    // Little-endian PCM16 — ghép 2 byte thành 1 sample có dấu.
                    val low = bytes[i].toInt() and 0xFF
                    val high = bytes[i + 1].toInt()
                    val sample = (high shl 8) or low
                    val absSample = kotlin.math.abs(sample)
                    if (absSample > maxAbs) maxAbs = absSample
                    i += 2
                }
                // Chuẩn hoá về thang [-1, 1] giống FloatArray gốc (chia
                // 32768 — đúng công thức nghịch đảo của floatToPcm16()
                // trong TtsAudioCache).
                maxAbs / 32768f
            }
        } catch (e: Exception) {
            Log.e(TAG, "readMaxAmplitude: lỗi đọc '${file.name}'", e)
            null
        }
    }
}