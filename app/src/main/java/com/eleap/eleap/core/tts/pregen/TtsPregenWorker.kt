// TtsPregenWorker.kt
// Đặt tại: com/eleap/eleap/core/tts/pregen/TtsPregenWorker.kt
//
// CoroutineWorker (WorkManager) — chạy NGẦM, generate sẵn file audio (.wav)
// cho từ/câu/cụm từ của các bài đọc bằng giọng Kokoro đang được chọn, để
// TtsPlaybackRouter (bước 8) có thể phát ngay từ cache thay vì generate
// on-the-fly (chậm với Kokoro).
//
// Theo đúng phong cách SyncPushWorker.kt/SyncPullWorker.kt: tự gọi init()
// cho MỌI phụ thuộc ngay trong doWork(), KHÔNG giả định MainActivity đã
// chạy trước — vì WorkManager có thể tự khởi động lại process chỉ để chạy
// job này (hệ thống kill app rồi restart process nền), lúc đó chưa có gì
// được init cả.
//
// ─────────────────────────────────────────────────────────────────────────
// ── NGUYÊN TẮC ƯU TIÊN (đã chốt) ────────────────────────────────────────
// ─────────────────────────────────────────────────────────────────────────
//   a) Có bài đang mở (TtsForegroundReading) → xử lý bài đó TRƯỚC, theo thứ
//      tự TỪ → CÂU → CỤM TỪ.
//   b) Xử lý xong bài đang mở (không còn item nào thiếu cache) → KHÔNG dừng
//      lại, tự động rơi xuống xử lý tiếp lịch sử (c).
//   c) Không có bài nào đang mở → duyệt lịch sử (TtsReadingHistory), sắp
//      xếp gần nhất → xa nhất.
//   d) TRƯỚC MỖI ITEM (không phải mỗi bài) phải kiểm tra lại: bài đang mở
//      có đổi không, giọng có đổi không. Nếu đổi → NGẮT NGAY, tính lại thứ
//      tự ưu tiên từ đầu với trạng thái MỚI.
//   e) Chạy liên tục, không giới hạn số item, không tự nghỉ giữa chừng —
//      chỉ dừng khi đã xử lý hết toàn bộ (bài đang mở + toàn bộ lịch sử) mà
//      không còn item nào thiếu cache cho giọng hiện tại.
//
// ─────────────────────────────────────────────────────────────────────────
// ── CƠ CHẾ "NGẮT VÀ LÀM LẠI TỪ ĐẦU" ─────────────────────────────────────
// ─────────────────────────────────────────────────────────────────────────
// Dùng 1 exception nội bộ (PregenRestartSignal) để unwind toàn bộ vòng lặp
// lồng nhau (bài → loại item → item) về lại vòng lặp NGOÀI CÙNG trong
// doWork() — nơi tính lại thứ tự ưu tiên (buildProcessingOrder) và sid mục
// tiêu MỚI rồi bắt đầu lại. Đơn giản hơn nhiều so với việc phải "return" một
// trạng thái đặc biệt xuyên qua 3 tầng vòng lặp for lồng nhau.
//
// Lưu ý kỹ thuật đã chốt: generate() của Kokoro là JNI blocking, KHÔNG
// cancel được giữa chừng (xem KokoroTtsEngine.kt) — nên việc "ngắt ngay" ở
// đây CHỈ có thể ngắt TRƯỚC KHI BẮT ĐẦU generate 1 item mới, không thể huỷ
// 1 item đang generate dở. Đây là giới hạn kỹ thuật chấp nhận được, không
// phải lỗi thiết kế.
package com.eleap.eleap.core.tts.pregen

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.eleap.eleap.core.tts.TtsManager

private const val TAG = "TtsPregenWorker"

// ── Exception nội bộ dùng để unwind vòng lặp khi phát hiện bài đang mở
// hoặc giọng đã đổi giữa chừng — KHÔNG phải lỗi thật, chỉ là tín hiệu điều
// khiển luồng, nên bắt riêng bằng catch (e: PregenRestartSignal), tách biệt
// hẳn với catch (e: Exception) chung ở doWork() (lỗi thật sự, ví dụ I/O).
private class PregenRestartSignal : Exception()

class TtsPregenWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            // ── Init mọi phụ thuộc — idempotent, an toàn gọi lại nhiều lần
            // (giống TtsReadingHistory.init()/TtsVoiceSnapshot.init() chỉ
            // gán lại đúng SharedPreferences, không tạo tác dụng phụ). ─────
            TtsReadingHistory.init(applicationContext)
            TtsVoiceSnapshot.init(applicationContext)

            // ── Đợi Kokoro sẵn sàng — nếu không (máy yếu, model lỗi, hoặc
            // hết timeout) → coi như "không có gì để làm" ở lượt chạy này,
            // KHÔNG phải lỗi, không retry (lần enqueue kế tiếp — vd mở lại
            // app — sẽ tự thử lại). ─────────────────────────────────────
            val kokoroReady = TtsManager.ensureKokoroReady(applicationContext)
            if (!kokoroReady) {
                Log.d(TAG, "doWork: Kokoro chưa sẵn sàng (timeout), bỏ qua lượt chạy này")
                return Result.success()
            }

            runPregenLoop(applicationContext)

            Log.d(TAG, "doWork: đã xử lý hết toàn bộ item cần cache, hoàn tất")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "doWork: lỗi không mong đợi, sẽ retry", e)
            Result.retry()
        }
    }

    // ── Vòng lặp NGOÀI CÙNG — mỗi lần bị PregenRestartSignal ngắt, tính lại
    // TOÀN BỘ trạng thái (sid mục tiêu, thứ tự ưu tiên) rồi chạy lại từ đầu
    // danh sách. Thoát vòng lặp (return) khi 1 lượt chạy hết toàn bộ danh
    // sách mà KHÔNG bị ngắt giữa chừng — nghĩa là đã xử lý xong hết, không
    // còn gì để làm nữa. ────────────────────────────────────────────────
    private suspend fun runPregenLoop(context: Context) {
        while (true) {
            // sid mục tiêu MỚI nhất tại thời điểm bắt đầu lượt này — null
            // nghĩa là hiện KHÔNG phải Kokoro đang active (đã đổi sang
            // Android TTS giữa chừng) → dừng hẳn Worker, không có gì để
            // pre-cache lúc này.
            val sid = TtsVoiceSnapshot.currentTargetSid() ?: run {
                Log.d(TAG, "runPregenLoop: engine hiện tại không phải Kokoro, dừng")
                return
            }

            val order = buildProcessingOrder(context)
            if (order.isEmpty()) {
                Log.d(TAG, "runPregenLoop: không có bài đọc nào (foreground lẫn lịch sử), dừng")
                return
            }

            // Chụp lại trạng thái NGAY TRƯỚC KHI bắt đầu xử lý danh sách —
            // dùng làm mốc so sánh ở checkNotInterrupted() cho TOÀN BỘ lượt
            // chạy này. Nếu 1 trong 2 giá trị này đổi bất kỳ lúc nào trong
            // lúc đang xử lý → PregenRestartSignal được ném ra, quay lại
            // đầu vòng lặp while để tính lại từ đầu với trạng thái mới.
            val snapshotForegroundId = TtsForegroundReading.currentReadingId.value
            val snapshotSelectedAt = TtsVoiceSnapshot.currentSelectedAtMillis()

            try {
                for (ref in order) {
                    processReading(context, ref, sid, snapshotForegroundId, snapshotSelectedAt)
                }
                // Duyệt hết toàn bộ order mà không bị ngắt → không còn gì
                // thiếu cache cho giọng hiện tại, hoàn tất.
                return
            } catch (e: PregenRestartSignal) {
                Log.d(TAG, "runPregenLoop: bị ngắt (bài đang mở hoặc giọng đã đổi), tính lại từ đầu")
                // Quay lại đầu while — KHÔNG log là lỗi, đây là hành vi
                // bình thường theo thiết kế mục 6d.
            }
        }
    }

    // ── Thứ tự ưu tiên: bài đang mở (nếu có) trước, rồi tới lịch sử (loại
    // bỏ trùng nếu bài đang mở cũng nằm trong lịch sử) — sắp theo gần nhất
    // → xa nhất. Cần tra cứu TtsReadingRef (kèm nguồn SYSTEM/MY_READING) từ
    // TtsReadingContentReader.getAllReadingIds() vì TtsForegroundReading/
    // TtsReadingHistory chỉ lưu readingId thô (String), không biết nguồn. ──
    private fun buildProcessingOrder(context: Context): List<TtsReadingRef> {
        val allRefs = TtsReadingContentReader.getAllReadingIds(context)
        val refsById = allRefs.associateBy { it.readingId }

        val order = mutableListOf<TtsReadingRef>()
        val addedIds = mutableSetOf<String>()

        val foregroundId = TtsForegroundReading.currentReadingId.value
        if (foregroundId != null) {
            refsById[foregroundId]?.let {
                order.add(it)
                addedIds.add(it.readingId)
            }
        }

        val historyIds = TtsReadingHistory.getHistorySortedByRecent()
        for (id in historyIds) {
            if (id in addedIds) continue
            refsById[id]?.let {
                order.add(it)
                addedIds.add(it.readingId)
            }
        }

        return order
    }

    // ── Xử lý TRỌN VẸN 1 bài — theo đúng thứ tự TỪ → CÂU → CỤM TỪ. Mỗi
    // item đều đi qua checkNotInterrupted() TRƯỚC KHI generate — đây chính
    // là điểm "kiểm tra trước mỗi item" theo mục 6d. ─────────────────────
    private suspend fun processReading(
        context: Context,
        ref: TtsReadingRef,
        sid: Int,
        snapshotForegroundId: String?,
        snapshotSelectedAt: Long,
    ) {
        val words = TtsReadingContentReader.getWordsForReading(context, ref)
        for (word in words) {
            checkNotInterrupted(snapshotForegroundId, snapshotSelectedAt)
            processItem(
                context = context,
                readingId = ref.readingId,
                sid = sid,
                type = TtsCacheItemType.WORD,
                itemId = word.wordId,
                text = word.textEn,
            )
        }

        val sentences = TtsReadingContentReader.getSentencesForReading(context, ref)
        for (sentence in sentences) {
            checkNotInterrupted(snapshotForegroundId, snapshotSelectedAt)
            processItem(
                context = context,
                readingId = ref.readingId,
                sid = sid,
                type = TtsCacheItemType.SENTENCE,
                itemId = sentence.sentenceId,
                text = sentence.textEn,
            )
        }

        val phrases = TtsReadingContentReader.getPhrasesForReading(context, ref)
        for (phrase in phrases) {
            checkNotInterrupted(snapshotForegroundId, snapshotSelectedAt)
            processItem(
                context = context,
                readingId = ref.readingId,
                sid = sid,
                type = TtsCacheItemType.PHRASE,
                itemId = phrase.phraseId,
                text = phrase.textEn,
            )
        }
    }

    // ── So sánh trạng thái HIỆN TẠI với snapshot đã chụp lúc bắt đầu lượt
    // chạy — nếu bài đang mở đổi HOẶC giọng đổi (TtsVoiceSnapshot.hasChangedSince)
    // thì ném PregenRestartSignal để runPregenLoop() bắt và tính lại từ đầu. ─
    private fun checkNotInterrupted(snapshotForegroundId: String?, snapshotSelectedAt: Long) {
        val currentForegroundId = TtsForegroundReading.currentReadingId.value
        if (currentForegroundId != snapshotForegroundId) {
            throw PregenRestartSignal()
        }
        if (TtsVoiceSnapshot.hasChangedSince(snapshotSelectedAt)) {
            throw PregenRestartSignal()
        }
    }

    // ── Xử lý 1 item cụ thể: đã có cache đúng hash thì bỏ qua, chưa có thì
    // generate rồi lưu. Generate thất bại (null, vd lỗi JNI) chỉ log cảnh
    // báo rồi BỎ QUA item đó — KHÔNG throw, vì đây là lỗi của riêng 1 item,
    // không nên làm gãy cả lượt xử lý; item này đơn giản là chưa có cache
    // và sẽ được thử lại ở lượt chạy Worker kế tiếp (do vẫn chưa tồn tại
    // file đúng hash trên đĩa — đúng cơ chế "resume" tự nhiên đã chốt). ────
    private suspend fun processItem(
        context: Context,
        readingId: String,
        sid: Int,
        type: TtsCacheItemType,
        itemId: String,
        text: String,
    ) {
        val hash = TtsAudioCache.contentHash(text)

        if (TtsAudioCache.hasCached(context, readingId, sid, type, itemId, hash)) {
            return
        }

        val audio = TtsManager.generateKokoroAudioForCache(text, sid)
        if (audio == null) {
            Log.w(
                TAG,
                "processItem: generate thất bại, bỏ qua item type=${type.prefix} id=$itemId " +
                        "(reading=$readingId, sid=$sid) — sẽ thử lại ở lượt chạy sau"
            )
            return
        }

        val saved = TtsAudioCache.saveGenerated(
            context = context,
            readingId = readingId,
            sid = sid,
            type = type,
            itemId = itemId,
            text = text,
            samples = audio.samples,
            sampleRate = audio.sampleRate,
        )
        if (saved == null) {
            Log.w(
                TAG,
                "processItem: lưu cache thất bại cho item type=${type.prefix} id=$itemId " +
                        "(reading=$readingId, sid=$sid) — sẽ thử lại ở lượt chạy sau"
            )
        }
    }
}