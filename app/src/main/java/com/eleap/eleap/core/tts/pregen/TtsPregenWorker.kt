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
// ── CƠ CHẾ PHÁT HIỆN & TỰ SỬA FILE CÂM (đã chốt, KHÔNG có "bỏ cuộc
// ── vĩnh viễn" — xem TtsCacheAuditor.kt/TtsVoicePairing.kt) ─────────────
// ─────────────────────────────────────────────────────────────────────────
// Audit (TtsCacheAuditor.audit()) chạy ở 2 thời điểm, LUÔN LUÔN, không có
// giới hạn số lần chạy lại theo thời gian:
//   1. Đầu processReading() — tức MỖI LẦN bài này được đưa vào xử lý (mỗi
//      lần mở lại bài / mỗi lần Worker resume) — audit toàn bộ item của bài.
//   2. Sau khi xử lý xong MỖI CÂU (words → sentence → phrases của câu đó) —
//      audit riêng các item của ĐÚNG câu vừa xử lý, bắt ngay các file câm
//      mới phát sinh trong lượt generate vừa rồi, không cần chờ tới lượt
//      audit toàn bài kế tiếp.
//
// Item bị phát hiện câm được xử lý bằng repairSilentItem(): thử lại theo
// CHUỖI GIỌNG (giọng hiện tại + tối đa 2 giọng còn lại CÙNG NHÓM, xem
// TtsVoicePairing.fallbackChainOf()) — mỗi giọng thử tối đa
// TtsCacheAuditor.MAX_ATTEMPTS_PER_VOICE lần, kiểm tra câm/hết câm NGAY SAU
// MỖI LẦN, dừng sớm nếu hết câm. Audio sinh thành công bằng giọng KHÁC
// giọng gốc được lưu vào ĐÚNG cache của giọng đó (không phải giọng gốc) —
// vì cache tách theo (readingId, sid). Hết toàn bộ chuỗi mà vẫn câm → KHÔNG
// đánh dấu gì cả, chỉ log, để lượt audit kế tiếp (câu sau, hoặc lần mở bài
// sau) tự phát hiện lại và thử lại từ đầu — không có trạng thái "bỏ cuộc
// vĩnh viễn" nào trong toàn bộ thiết kế này.
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
// phải lỗi thiết kế. Áp dụng ĐÚNG như vậy cho từng lần thử trong chuỗi
// giọng của repairSilentItem() — checkNotInterrupted() được gọi TRƯỚC MỖI
// LẦN THỬ (không chỉ trước mỗi item), vì 1 item có thể tốn tới 9 lần
// generate (3 giọng × 3 lần) — vài phút — nên cần phản ứng kịp thời nếu
// người dùng đổi bài/giọng giữa chừng, thay vì phải chờ hết cả chuỗi.
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

    // ── Xử lý TRỌN VẸN 1 bài — theo đúng thứ tự TỪNG CÂU: các TỪ của câu →
    // chính CÂU đó → các CỤM TỪ của câu, rồi mới sang câu kế tiếp (lặp lại
    // cho tới hết câu cuối). Ưu tiên để người đọc câu đầu tiên có đủ audio
    // (từ/câu/cụm) sớm nhất, thay vì phải chờ generate hết từ của TOÀN BỘ
    // bài trước khi có được 1 câu hoàn chỉnh nào.
    //
    // Lấy words/phrases 1 LẦN cho cả bài (đỡ query lặp lại theo từng câu)
    // rồi group theo sentenceId bằng groupBy — words/phrases của 1 câu được
    // lấy qua map tra cứu O(1). Mỗi item vẫn đi qua checkNotInterrupted()
    // TRƯỚC KHI generate — đúng điểm "kiểm tra trước mỗi item" theo mục 6d.
    //
    // ⚠️ MỚI: audit chạy ở 2 chỗ trong hàm này — (1) NGAY ĐẦU, audit toàn bộ
    // bài (như cũ), và (2) SAU MỖI CÂU, audit riêng đúng các item của câu
    // vừa xử lý — xem auditAndRepairSentence() bên dưới.
    private suspend fun processReading(
        context: Context,
        ref: TtsReadingRef,
        sid: Int,
        snapshotForegroundId: String?,
        snapshotSelectedAt: Long,
    ) {
        val sentences = TtsReadingContentReader.getSentencesForReading(context, ref)
        val wordsBySentence = TtsReadingContentReader.getWordsForReading(context, ref)
            .groupBy { it.sentenceId }
        val phrasesBySentence = TtsReadingContentReader.getPhrasesForReading(context, ref)
            .groupBy { it.sentenceId }

        // ── (1) Audit toàn bộ cache ĐÃ CÓ của bài này TRƯỚC KHI chạy vòng
        // lặp tuần tự bình thường — bắt các file câm còn sót lại từ trước
        // (cache cũ tạo trước khi có check biên độ, hoặc lỗi ghi file dở
        // dang, hoặc từ lượt trước chưa sửa hết được) mà vòng lặp tuần tự
        // sẽ KHÔNG bao giờ tự phát hiện lại (vì hasCached() chỉ kiểm tra
        // file tồn tại, không đọc nội dung). Đây chính là điểm audit "mỗi
        // lần mở lại bài đọc" đã chốt — processReading() được gọi lại mỗi
        // khi bài này được đưa vào order xử lý (mở bài mới, hoặc Worker
        // resume sau khi bị ngắt). ──────────────────────────────────────
        checkNotInterrupted(snapshotForegroundId, snapshotSelectedAt)
        auditAndRepairReading(
            context = context,
            ref = ref,
            sid = sid,
            sentences = sentences,
            wordsBySentence = wordsBySentence,
            phrasesBySentence = phrasesBySentence,
            snapshotForegroundId = snapshotForegroundId,
            snapshotSelectedAt = snapshotSelectedAt,
        )

        for (sentence in sentences) {
            val words = wordsBySentence[sentence.sentenceId].orEmpty()
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

            checkNotInterrupted(snapshotForegroundId, snapshotSelectedAt)
            processItem(
                context = context,
                readingId = ref.readingId,
                sid = sid,
                type = TtsCacheItemType.SENTENCE,
                itemId = sentence.sentenceId,
                text = sentence.textEn,
            )

            val phrases = phrasesBySentence[sentence.sentenceId].orEmpty()
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

            // ── (2) MỚI: audit lại đúng các item của CÂU VỪA XONG, ngay
            // trước khi sang câu kế tiếp — bắt các file câm MỚI phát sinh
            // ngay trong lượt generate của processItem() ở trên (Kokoro
            // vẫn ghi file dù câm — logAmplitudeCheck() ở KokoroTtsEngine
            // chỉ LOG cảnh báo, không chặn lưu), không cần chờ tới lượt
            // audit toàn bài kế tiếp (có thể rất lâu sau, nếu bài dài). ───
            checkNotInterrupted(snapshotForegroundId, snapshotSelectedAt)
            auditAndRepairSentence(
                context = context,
                ref = ref,
                sid = sid,
                sentence = sentence,
                words = words,
                phrases = phrases,
                snapshotForegroundId = snapshotForegroundId,
                snapshotSelectedAt = snapshotSelectedAt,
            )
        }
    }

    // ── Audit + repair cho TOÀN BỘ item của 1 bài — gom hết words/sentences/
    // phrases của mọi câu thành 1 danh sách TtsAuditItem rồi giao cho
    // auditAndRepairItems() (dùng chung với auditAndRepairSentence() bên
    // dưới) xử lý. ─────────────────────────────────────────────────────────
    private suspend fun auditAndRepairReading(
        context: Context,
        ref: TtsReadingRef,
        sid: Int,
        sentences: List<TtsSentenceItem>,
        wordsBySentence: Map<String, List<TtsWordItem>>,
        phrasesBySentence: Map<String, List<TtsPhraseItem>>,
        snapshotForegroundId: String?,
        snapshotSelectedAt: Long,
    ) {
        val auditItems = mutableListOf<TtsAuditItem>()
        for (sentence in sentences) {
            wordsBySentence[sentence.sentenceId].orEmpty().forEach {
                auditItems.add(TtsAuditItem(TtsCacheItemType.WORD, it.wordId, it.textEn))
            }
            auditItems.add(TtsAuditItem(TtsCacheItemType.SENTENCE, sentence.sentenceId, sentence.textEn))
            phrasesBySentence[sentence.sentenceId].orEmpty().forEach {
                auditItems.add(TtsAuditItem(TtsCacheItemType.PHRASE, it.phraseId, it.textEn))
            }
        }

        auditAndRepairItems(
            context = context,
            readingId = ref.readingId,
            sid = sid,
            items = auditItems,
            snapshotForegroundId = snapshotForegroundId,
            snapshotSelectedAt = snapshotSelectedAt,
            logScope = "toàn bài",
        )
    }

    // ── MỚI: audit + repair cho ĐÚNG 1 CÂU (words + chính câu + phrases của
    // câu đó) — gọi ngay sau khi processReading() xử lý xong 1 câu, TRƯỚC
    // khi sang câu kế tiếp. Dùng chung auditAndRepairItems() với hàm ở
    // trên — chỉ khác phạm vi danh sách items truyền vào. ──────────────────
    private suspend fun auditAndRepairSentence(
        context: Context,
        ref: TtsReadingRef,
        sid: Int,
        sentence: TtsSentenceItem,
        words: List<TtsWordItem>,
        phrases: List<TtsPhraseItem>,
        snapshotForegroundId: String?,
        snapshotSelectedAt: Long,
    ) {
        val auditItems = mutableListOf<TtsAuditItem>()
        words.forEach { auditItems.add(TtsAuditItem(TtsCacheItemType.WORD, it.wordId, it.textEn)) }
        auditItems.add(TtsAuditItem(TtsCacheItemType.SENTENCE, sentence.sentenceId, sentence.textEn))
        phrases.forEach { auditItems.add(TtsAuditItem(TtsCacheItemType.PHRASE, it.phraseId, it.textEn)) }

        auditAndRepairItems(
            context = context,
            readingId = ref.readingId,
            sid = sid,
            items = auditItems,
            snapshotForegroundId = snapshotForegroundId,
            snapshotSelectedAt = snapshotSelectedAt,
            logScope = "câu ${sentence.sentenceId}",
        )
    }

    // ── Điểm DÙNG CHUNG cho cả 2 hàm audit ở trên — gọi TtsCacheAuditor.audit()
    // với đúng danh sách items được truyền vào (phạm vi toàn bài hoặc chỉ 1
    // câu), rồi repairSilentItem() TỪNG item câm một. logScope chỉ để log dễ
    // phân biệt đang audit ở phạm vi nào khi xem logcat. ────────────────────
    private suspend fun auditAndRepairItems(
        context: Context,
        readingId: String,
        sid: Int,
        items: List<TtsAuditItem>,
        snapshotForegroundId: String?,
        snapshotSelectedAt: Long,
        logScope: String,
    ) {
        if (items.isEmpty()) return

        val silentItems = TtsCacheAuditor.audit(context, readingId, sid, items)
        if (silentItems.isEmpty()) return

        Log.w(
            TAG,
            "auditAndRepairItems[$logScope]: phát hiện ${silentItems.size} file câm ở reading=$readingId " +
                    "sid=$sid, bắt đầu xử lý lại"
        )

        for (silentItem in silentItems) {
            checkNotInterrupted(snapshotForegroundId, snapshotSelectedAt)
            repairSilentItem(context, readingId, sid, silentItem, snapshotForegroundId, snapshotSelectedAt)
        }
    }

    // ── ⚠️ SỬA: thử regenerate lại 1 item đã biết là câm, theo CHUỖI GIỌNG
    // — chuỗi này là [sidHiệnTại] + TtsVoicePairing.fallbackChainOf(sidHiệnTại),
    // tức tối đa 3 giọng (giọng đang generate + 2 giọng còn lại CÙNG NHÓM,
    // nếu sid này nằm trong 1 nhóm đã định nghĩa — xem TtsVoicePairing.kt).
    // Với MỖI giọng trong chuỗi: thử tối đa TtsCacheAuditor.MAX_ATTEMPTS_PER_VOICE
    // lần, kiểm tra biên độ NGAY SAU MỖI LẦN (in-memory, trên samples vừa
    // sinh — KHÔNG cần ghi file rồi đọc lại mới biết), dừng ngay khi hết
    // câm. Hết số lần của 1 giọng mà vẫn câm → chuyển sang giọng kế tiếp
    // trong chuỗi (nếu còn); hết toàn bộ chuỗi mà vẫn câm → KHÔNG đánh dấu
    // gì cả, chỉ log, để lượt audit kế tiếp tự phát hiện lại (không có
    // trạng thái "bỏ cuộc vĩnh viễn" trong thiết kế này — xem
    // TtsCacheAuditor.kt).
    //
    // ⚠️ SỬA (quan trọng): dù thử THÀNH CÔNG bằng giọng nào trong chuỗi
    // (kể cả 1 giọng khác sid gốc), audio đó LUÔN được lưu vào ĐÚNG cache
    // của SID GỐC (sid, không phải trySid) — tức GHI ĐÈ thẳng lên file câm
    // cũ. Không còn lưu vào thư mục riêng của giọng "cứu" nữa — vì số file
    // câm thực tế không nhiều, nên không cần TtsPlaybackRouter dò theo
    // chuỗi giọng lúc phát; cache của sid gốc luôn tự chứa đủ audio (có thể
    // là do giọng khác generate ra, nhưng nằm đúng slot của sid gốc).
    //
    // checkNotInterrupted() được gọi TRƯỚC MỖI LẦN THỬ (không chỉ trước mỗi
    // item) — vì cả chuỗi có thể tốn tới 9 lần generate (~vài phút), cần
    // phản ứng kịp thời nếu người dùng đổi bài/giọng giữa chừng.
    private suspend fun repairSilentItem(
        context: Context,
        readingId: String,
        sid: Int,
        silentItem: TtsSilentItem,
        snapshotForegroundId: String?,
        snapshotSelectedAt: Long,
    ) {
        val item = silentItem.item
        val sidChain = listOf(sid) + TtsVoicePairing.fallbackChainOf(sid)

        Log.d(
            TAG,
            "repairSilentItem: bắt đầu xử lý lại type=${item.type.prefix} id=${item.itemId} " +
                    "text=\"${item.text}\" (reading=$readingId, biên độ cũ=${silentItem.maxAmplitude}), " +
                    "chuỗi giọng thử=${sidChain.joinToString(" → ") { TtsVoicePairing.displayName(it) }}"
        )

        for (trySid in sidChain) {
            for (attempt in 1..TtsCacheAuditor.MAX_ATTEMPTS_PER_VOICE) {
                checkNotInterrupted(snapshotForegroundId, snapshotSelectedAt)

                Log.d(
                    TAG,
                    "repairSilentItem: thử ${TtsVoicePairing.displayName(trySid)} " +
                            "lần $attempt/${TtsCacheAuditor.MAX_ATTEMPTS_PER_VOICE} cho id=${item.itemId}..."
                )

                val audio = TtsManager.generateKokoroAudioForCache(item.text, trySid)
                if (audio == null) {
                    Log.w(
                        TAG,
                        "repairSilentItem: generate thất bại (null) ở " +
                                "${TtsVoicePairing.displayName(trySid)} lần $attempt cho id=${item.itemId}"
                    )
                    continue
                }

                val amplitude = maxAmplitudeOf(audio.samples)
                if (amplitude < TtsCacheAuditor.SILENCE_AMPLITUDE_THRESHOLD) {
                    Log.w(
                        TAG,
                        "repairSilentItem: ${TtsVoicePairing.displayName(trySid)} lần $attempt " +
                                "vẫn CÂM (biên độ=$amplitude) cho id=${item.itemId}"
                    )
                    continue
                }

                // ⚠️ SỬA: luôn lưu vào cache của SID GỐC (sid), KHÔNG phải
                // trySid — dù audio này được generate bằng giọng khác, nó
                // sẽ ghi đè thẳng lên file câm cũ trong đúng slot cache của
                // giọng gốc (cùng hash vì text không đổi). Nhờ vậy
                // TtsPlaybackRouter không cần dò theo chuỗi giọng khi phát.
                val saved = TtsAudioCache.saveGenerated(
                    context = context,
                    readingId = readingId,
                    sid = sid,
                    type = item.type,
                    itemId = item.itemId,
                    text = item.text,
                    samples = audio.samples,
                    sampleRate = audio.sampleRate,
                )
                if (saved != null) {
                    Log.d(
                        TAG,
                        "repairSilentItem: THÀNH CÔNG bằng ${TtsVoicePairing.displayName(trySid)} " +
                                "lần $attempt cho id=${item.itemId} (biên độ mới=$amplitude)" +
                                if (trySid != sid) " — đã ghi đè vào cache của giọng GỐC (${TtsVoicePairing.displayName(sid)})" else " — đã ghi đè file câm cũ"
                    )
                } else {
                    Log.w(
                        TAG,
                        "repairSilentItem: generate ra tiếng nhưng LƯU FILE thất bại cho id=${item.itemId} " +
                                "(${TtsVoicePairing.displayName(trySid)})"
                    )
                }
                return
            }
            Log.w(
                TAG,
                "repairSilentItem: hết ${TtsCacheAuditor.MAX_ATTEMPTS_PER_VOICE} lần ở " +
                        "${TtsVoicePairing.displayName(trySid)} vẫn câm cho id=${item.itemId}, " +
                        "chuyển sang giọng kế tiếp trong chuỗi (nếu còn)"
            )
        }

        Log.e(
            TAG,
            "repairSilentItem: HẾT TOÀN BỘ chuỗi giọng (${sidChain.joinToString(", ") { TtsVoicePairing.displayName(it) }}) " +
                    "vẫn CÂM cho type=${item.type.prefix} id=${item.itemId} text=\"${item.text}\" — " +
                    "KHÔNG đánh dấu gì, chờ lượt audit kế tiếp tự thử lại"
        )
    }

    // ── Tính biên độ tối đa trên FloatArray samples vừa generate trong RAM —
    // CÙNG công thức/ngưỡng với TtsCacheAuditor.readMaxAmplitude() (đọc từ
    // file PCM16) và KokoroTtsEngine.logAmplitudeCheck(), chỉ khác nguồn dữ
    // liệu đầu vào (FloatArray thay vì byte trên đĩa). ─────────────────────
    private fun maxAmplitudeOf(samples: FloatArray): Float {
        var maxAbs = 0f
        for (s in samples) {
            val abs = kotlin.math.abs(s)
            if (abs > maxAbs) maxAbs = abs
        }
        return maxAbs
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
    // file đúng hash trên đĩa — đúng cơ chế "resume" tự nhiên đã chốt).
    //
    // ⚠️ Lưu ý: hàm này KHÔNG tự kiểm tra biên độ / KHÔNG tự retry nếu ra
    // audio câm — generate xong là lưu thẳng (Kokoro vẫn ghi file dù câm,
    // xem KokoroTtsEngine.logAmplitudeCheck()). Việc phát hiện + sửa file
    // câm hoàn toàn do lớp audit (auditAndRepairSentence() ngay sau khi
    // vòng lặp câu này chạy processItem() xong) đảm nhiệm — tách biệt rõ 2
    // trách nhiệm: processItem() chỉ lo "generate nếu chưa có cache", audit
    // lo "phát hiện + sửa cache đã có nhưng câm". ────────────────────────
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