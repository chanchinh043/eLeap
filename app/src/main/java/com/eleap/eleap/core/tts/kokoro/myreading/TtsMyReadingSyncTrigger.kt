// TtsMyReadingSyncTrigger.kt
// Đặt tại: com/eleap/eleap/core/tts/kokoro/myreading/TtsMyReadingSyncTrigger.kt
//
// Nơi DUY NHẤT quyết định KHI NÀO gọi TtsMyReadingRequestClient.requestSynthesis()
// — tách khỏi MyReadingSyncEngine để engine đó KHÔNG cần biết gì về Kokoro/
// TTS (đúng tinh thần: MyReadingSyncEngine chỉ lo đồng bộ readings/sentences/
// phrases/words, không lo audio). MyReadingSyncEngine chỉ cần gọi ĐÚNG 1 hàm
// ở đây sau khi push thành công, mọi quyết định "gọi hay không, gọi cho sid
// nào" nằm hết trong file này.
//
// ⚠️ ĐIỀU KIỆN KÍCH HOẠT (đường CHÍNH, onReadingsSynced) — CẢ HAI phải đúng:
//   (a) reading.isAiProcessed == true — bài phải đã qua AI dịch xong (có đủ
//       title_vi/phrases/words), server tổng hợp giọng cho bài CHƯA dịch là
//       vô nghĩa (không có gì để đọc khớp với UI).
//   (b) VỪA push thành công lên Supabase trong lượt push này (không phải
//       "đã từng đồng bộ trước đó rồi giờ mới chợt nhớ ra") — vì mục đích
//       chính là "phát hiện SỰ KIỆN vừa đồng bộ xong", không phải quét toàn
//       bộ bài mỗi lần sync. Do đó CHỈ gọi cho đúng những readingId có mặt
//       trong succeededIds của lượt pushPendingLocked() vừa chạy — xem hàm
//       onReadingsSynced() bên dưới, gọi tại MyReadingSyncEngine. (Ngoại lệ
//       riêng cho GUEST — xem MyReadingAiProcessor.kt: gọi thẳng ngay sau
//       khi AI xử lý xong, vì guest không bao giờ đi qua đường push Supabase.)
//
// ⚠️ CÒN 1 ĐƯỜNG VÀO NỮA — onVoiceChangedForReading() — dùng khi người dùng
// ĐỔI GIỌNG ngay trong 1 bài MyReading đang mở (xem TtsVoicePickerScreen).
// Khác onReadingsSynced() ở chỗ: không cần "vừa push xong", chỉ cần bài đó
// đã isAiProcessed — vì mục đích là "giọng MỚI chưa từng được xin cho bài
// này", không phải "phát hiện sự kiện sync". Dedup vẫn dùng chung
// TtsMyReadingSentRequestStore (theo sid) nên không lo gửi trùng.
//
// ⚠️ CHỈ XIN CHO 1 GIỌNG — giọng người dùng ĐANG CHỌN hiện tại
// (TtsVoiceSnapshot.currentVendor()/currentSid()), KHÔNG xin sẵn toàn bộ 53
// giọng Kokoro cho mỗi bài. Lý do: nhu cầu thực tế là "khi mở bài, có giọng
// đang chọn để nghe", xin dư thừa mọi giọng vừa tốn tài nguyên server vừa
// không chắc người dùng bao giờ chọn tới. Nếu người dùng đổi giọng sau này,
// TtsVoicePickerScreen tự gọi onVoiceChangedForReading() cho bài đang mở —
// không cần lo trước ở đây.
//
// ⚠️ CHỈ ÁP DỤNG VENDOR KOKORO — giống lý do TtsVoicePickerScreen chỉ enqueue
// TtsKokoroPackScheduler khi vendor == KOKORO (xem TtsVoicePickerScreen.kt),
// vendor khác (nếu có sau này) tự có cơ chế riêng, không đi qua đây.
//
// ⚠️ BẢN CẬP NHẬT — DEVICE GỬI TEXT TRỰC TIẾP, KHÔNG QUA SUPABASE: server
// KHÔNG BAO GIỜ tự hỏi Supabase để lấy text (xem ghi chú "redesign" ở
// TtsMyReadingRequestClient.kt) — toàn bộ text cần tổng hợp (items:
// sentence/word/phrase) được build NGAY TẠI ĐÂY từ `sentences` đã có sẵn
// (cùng nguồn dùng để tính contentHash) và gửi kèm thẳng trong request. Nhờ
// vậy hoạt động y hệt cho CẢ guest lẫn user thật — không phụ thuộc bài đã
// push lên Supabase hay chưa.
//
// ⚠️ MỚI — CHỐNG POLLING TRÙNG LẶP VỚI TtsMyReadingDownloadGate:
// Trước đây có 2 nguồn ĐỘC LẬP cùng gọi checkStatus() cho CÙNG 1 job trong
// lúc server đang xử lý: (1) pollUntilReadyThenDownload() ở file này (mỗi
// 4 giây, tối đa 60 giây) VÀ (2) TtsMyReadingDownloadGate.shouldProceedToDriveSync()
// (gọi mỗi khi ReadingScreen mở/reload — có thể mỗi 15 giây do AI watchdog
// — xem comment ở đó). Nếu người dùng mở đúng bài vừa AI dịch xong trong
// vòng 60 giây đầu, CẢ HAI cùng hỏi server 1 câu hỏi giống hệt nhau, dư
// thừa. `activelyPolling` (in-memory, sống theo process — KHÔNG cần bền
// vững qua restart vì chỉ có tác dụng "khỏi hỏi trùng trong cùng 1 phiên
// polling đang chạy") đánh dấu job nào đang có 1 luồng poll xử lý, để Gate
// tự biết "đã có người lo job này rồi, khỏi tự hỏi nữa" — xem isPolling()
// và cách TtsMyReadingDownloadGate gọi nó.
//
// Hàm KHÔNG throw ra ngoài — lỗi ở đây (gọi server thất bại) KHÔNG được làm
// hỏng lượt sync readings/sentences đang chạy, đúng nguyên tắc "core/tts/
// luôn là lưới an toàn tuỳ chọn". MyReadingSyncEngine gọi hàm này "bắn rồi
// quên" (fire-and-forget), không chặn/không throw ngược lên.
package com.eleap.eleap.core.tts.kokoro.myreading

import android.content.Context
import android.util.Log
import com.eleap.eleap.core.tts.TtsVendor
import com.eleap.eleap.core.tts.TtsVoiceSnapshot
import com.eleap.eleap.core.tts.kokoro.TtsKokoroPackScheduler
import com.eleap.eleap.feature.myreading.data.MyReadingRepository
import com.eleap.eleap.feature.reading.data.Reading
import com.eleap.eleap.feature.reading.data.ReadingSentence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "TtsMyReadingSyncTrigger"

// ── Cấu hình cho pollUntilReadyThenDownload() — 15 lần x 4 giây = tối đa 60
// giây polling sau khi 1 job chuyển sang pending/processing. Dựa theo log
// thực tế: server xử lý xong 1 bài ngắn (4 items) mất khoảng 25-50 giây —
// 60 giây có biên độ dư dả cho bài dài hơn mà không giữ tài nguyên quá lâu
// nếu server chậm bất thường (khi đó để TtsMyReadingPrecacheWorker tiếp tục
// theo dõi ở chu kỳ 15 phút sau).
private const val MAX_POLL_ATTEMPTS = 15
private const val POLL_INTERVAL_MS = 4_000L

// ── Scope RIÊNG của object này để launch polling NGẦM (fire-and-forget),
// KHÔNG dùng scope của caller (vd viewModelScope của ReadingViewModel hay
// coroutine của MyReadingSyncEngine) — vì poll có thể chạy tới 60 giây,
// launch trên scope của caller sẽ CHẶN các bài khác trong CÙNG vòng lặp
// onReadingsSynced() (khi sync hàng loạt nhiều bài cùng lúc) hoặc bị huỷ
// ngang nếu caller's scope kết thúc trước khi poll xong (vd
// TtsVoicePickerScreen đóng màn hình huỷ rememberCoroutineScope()).
// SupervisorJob — 1 job con lỗi (exception) không huỷ các job con khác
// đang poll song song cho bài khác. Sống suốt vòng đời process (đối tượng
// singleton) — chấp nhận được vì mỗi job con tự giới hạn tối đa 60 giây,
// không rò rỉ vô thời hạn.
private val pollScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

// ── MỚI — Đánh dấu job nào đang có 1 luồng pollUntilReadyThenDownload()
// XỬ LÝ, để TtsMyReadingDownloadGate biết mà tự bỏ qua, không tự hỏi server
// trùng lặp trong lúc luồng poll này còn sống. Key = "readingId|sid" (đủ để
// phân biệt, không cần contentHash — tại 1 thời điểm chỉ có ý nghĩa 1 job
// đang active cho mỗi (readingId, sid), xem TtsMyReadingPendingStore.add()
// cũng tự loại entry cũ theo đúng logic này).
// ConcurrentHashMap.newKeySet() — an toàn khi nhiều coroutine (nhiều bài
// đang poll song song trong 1 lượt sync hàng loạt) cùng add/remove.
private val activelyPolling = ConcurrentHashMap.newKeySet<String>()

private fun pollKey(readingId: String, sid: Int): String = "$readingId|$sid"

object TtsMyReadingSyncTrigger {

    // ── MỚI — Điểm gọi từ TtsMyReadingDownloadGate để biết "job này đang
    // có 1 luồng poll tự động xử lý rồi, có cần tự hỏi server nữa không".
    // true = ĐANG có luồng poll xử lý — Gate nên trả về false (bỏ qua lượt
    // gọi Drive sync lần này, để luồng poll kia tự enqueue tải khi ready).
    // false = KHÔNG có luồng poll nào — Gate an toàn để tự hỏi status như
    // bình thường (vd trường hợp app vừa khởi động lại, mất hết state poll
    // cũ trong RAM, nhưng job vẫn còn pending ở server).
    fun isPolling(readingId: String, sid: Int): Boolean =
        activelyPolling.contains(pollKey(readingId, sid))

    // ── Điểm gọi CHÍNH — gọi từ MyReadingSyncEngine.pushPendingLocked() NGAY
    // SAU KHI biết succeededIds của lượt push này (readingId nào vừa
    // create/update thành công lên Supabase), HOẶC từ MyReadingAiProcessor
    // ngay sau khi AI xử lý xong cho GUEST (xem ghi chú ở đó). Nhận thẳng
    // danh sách Reading đã push thành công (không phải chỉ readingId) để đọc
    // isAiProcessed mà không cần query lại DB thêm 1 lần.
    //
    // context: dùng để lấy MyReadingRepository (đọc sentences build items +
    // tính hash) và đọc BuildConfig — LUÔN truyền applicationContext ở nơi gọi.
    //
    // ⚠️ MỌI job trả về PENDING/PROCESSING đều tự động được poll NGẦM (xem
    // pollUntilReadyThenDownload(), launch trên pollScope riêng — KHÔNG
    // chặn vòng lặp for ở dưới, nên sync hàng loạt nhiều bài vẫn chạy nhanh
    // như cũ). Áp dụng ĐỒNG NHẤT cho CẢ 2 lối gọi (đồng bộ hàng loạt từ
    // MyReadingSyncEngine LẪN đổi giọng tương tác từ onVoiceChangedForReading)
    // — trước đây chỉ đổi giọng mới có polling (qua cờ activePoll), khiến
    // trường hợp "mở 1 bài MyReading MỚI lần đầu" (job tạo qua đường sync
    // thường, không phải đổi giọng) vẫn phải thoát ra mở lại mới thấy audio.
    suspend fun onReadingsSynced(
        context: Context,
        syncedReadings: List<Reading>,
    ) {
        val vendor = TtsVoiceSnapshot.currentVendor()
        if (vendor != TtsVendor.KOKORO) {
            // Giọng đang chọn không phải Kokoro (dự phòng cho tương lai có
            // thêm vendor khác) — cơ chế xin server pack-based này chỉ dành
            // riêng cho Kokoro, không áp dụng.
            return
        }

        val eligible = syncedReadings.filter { it.isAiProcessed }
        if (eligible.isEmpty()) return

        // TtsMyReadingConfig tự log cảnh báo (1 lần duy nhất) nếu chưa cấu
        // hình — ở đây chỉ cần return êm, không log lặp lại.
        val baseUrl = TtsMyReadingConfig.baseUrl() ?: return

        val sid = TtsVoiceSnapshot.currentSid()
        val client = TtsMyReadingRequestClient(baseUrl)
        val repo = MyReadingRepository.getInstance(context)

        for (reading in eligible) {
            try {
                val sentences = repo.getReading(reading.readingId)
                if (sentences.isEmpty()) {
                    Log.d(TAG, "onReadingsSynced: reading_id=${reading.readingId} chưa có câu nào, bỏ qua")
                    continue
                }

                val contentHash = TtsMyReadingContentHash.compute(sentences)

                // ── Đã gửi ĐÚNG contentHash này và được server xác nhận
                // (HTTP 200) ở lượt trigger TRƯỚC ĐÓ rồi → KHÔNG gửi lại.
                // Kiểm tra NGAY TRƯỚC khi build items (build items tốn công
                // duyệt toàn bộ sentences/phrases/words, bỏ sớm nếu biết
                // chắc không cần gửi). Nếu nội dung bài đã đổi (contentHash
                // khác lần gửi trước) hoặc đây là lần gửi ĐẦU TIÊN cho
                // (readingId, sid) này, hasSentSameContent() trả về false →
                // vẫn tiến hành gửi như bình thường. Cùng logic dedup này áp
                // dụng CHUNG cho cả 2 lối vào (onReadingsSynced VÀ
                // onVoiceChangedForReading bên dưới) — vì key lưu trữ gồm cả
                // sid, mỗi giọng có 1 dòng "đã gửi" riêng.
                if (TtsMyReadingSentRequestStore.hasSentSameContent(reading.readingId, sid, contentHash)) {
                    Log.d(
                        TAG,
                        "onReadingsSynced: reading_id=${reading.readingId} sid=$sid contentHash=$contentHash " +
                                "ĐÃ gửi và được server xác nhận trước đó, bỏ qua"
                    )
                    continue
                }

                val items = buildItems(sentences)
                if (items.isEmpty()) {
                    // Có sentences nhưng không câu nào có textEn hợp lệ — hiếm
                    // (dữ liệu lỗi), không có gì để server tổng hợp, bỏ qua
                    // thay vì gửi request rỗng vô ích (server vẫn tạo job
                    // nhưng chỉ log cảnh báo rồi mãi mãi "failed" ở bước 8).
                    Log.w(TAG, "onReadingsSynced: reading_id=${reading.readingId} có sentences nhưng build items rỗng, bỏ qua")
                    continue
                }

                val status = client.requestSynthesis(
                    readingId   = reading.readingId,
                    sid         = sid,
                    contentHash = contentHash,
                    items       = items,
                )

                if (status != null) {
                    // ── status khác null nghĩa là server đã trả HTTP 200 —
                    // request ĐÃ ĐƯỢC GHI VÀO DB CỦA SERVER (xem main.py: lỗi
                    // ghi DB giờ trả 503, KHÔNG còn trả status giả) — ĐÂY mới
                    // là thời điểm hợp lệ để ghi nhớ "đã gửi", đúng yêu cầu
                    // "sau khi lưu thành công nó sẽ báo lại cho device là đã
                    // nhận request để sau đó device không phải gửi lại nữa".
                    // Nếu status == null (lỗi mạng/server trả 503) — TUYỆT
                    // ĐỐI KHÔNG markSent(), để lần trigger sau (bài được sửa
                    // lần nữa, hoặc watchdog) tự thử gửi lại — xem ghi chú
                    // "ĐÃ XÁC NHẬN" ở TtsMyReadingSentRequestStore.kt.
                    TtsMyReadingSentRequestStore.markSent(reading.readingId, sid, contentHash)
                }

                if (status != null && status != TtsMyReadingJobStatus.READY) {
                    // Chưa ready ngay — ghi vào store để
                    // TtsMyReadingPrecacheWorker (chạy nền định kỳ) tự hỏi
                    // lại sau, không cần đợi user mở bài mới biết.
                    TtsMyReadingPendingStore.add(
                        TtsMyReadingPendingEntry(
                            readingId   = reading.readingId,
                            sid         = sid,
                            contentHash = contentHash,
                        )
                    )

                    // ⚠️ MỚI — launch poll NGẦM trên pollScope riêng (KHÔNG
                    // suspend trực tiếp ở đây — sẽ chặn vòng lặp for, làm
                    // chậm các bài khác nếu đang sync hàng loạt). Áp dụng
                    // cho MỌI job pending/processing bất kể gọi từ đâu (sync
                    // hàng loạt hay đổi giọng tương tác) — xem ghi chú đầy
                    // đủ ở chữ ký onReadingsSynced() và pollUntilReadyThenDownload().
                    if (status == TtsMyReadingJobStatus.PENDING || status == TtsMyReadingJobStatus.PROCESSING) {
                        pollScope.launch {
                            pollUntilReadyThenDownload(
                                context     = context,
                                client      = client,
                                readingId   = reading.readingId,
                                sid         = sid,
                                contentHash = contentHash,
                            )
                        }
                    }
                } else if (status == TtsMyReadingJobStatus.READY) {
                    // ⚠️ MỚI — server trả READY NGAY trong lượt xin này (vd
                    // đã từng xử lý sẵn đúng contentHash này trước đó, hoặc
                    // job cũ vừa kịp xong đúng lúc mình hỏi lại). Không cần
                    // đợi TtsMyReadingPrecacheWorker (chu kỳ 15 phút) mới
                    // biết — Drive CHẮC CHẮN đã có file lúc này (server chỉ
                    // trả "ready" sau khi upload Drive xong), nên enqueue tải
                    // NGAY. Dùng enqueueDownload() (per-sid) — KHÔNG PHẢI
                    // enqueueEnsureReadingSynced() — cùng lý do đã nêu ở
                    // TtsMyReadingPrecacheWorker.kt/ReadingScreen.kt: marker
                    // "đã tải ĐỦ cả bài" là vĩnh viễn, dùng nó ở đây sẽ chặn
                    // các giọng MyReading KHÁC được xin sau này.
                    Log.d(
                        TAG,
                        "onReadingsSynced: reading_id=${reading.readingId} sid=$sid READY ngay, enqueue tải Drive luôn"
                    )
                    TtsKokoroPackScheduler.enqueueDownload(context, reading.readingId, sid)
                }

                Log.d(
                    TAG,
                    "onReadingsSynced: đã xin tổng hợp reading_id=${reading.readingId} sid=$sid " +
                            "contentHash=$contentHash items=${items.size} → status=$status"
                )
            } catch (e: Exception) {
                // 1 bài lỗi (vd mất mạng giữa chừng) không chặn các bài còn
                // lại trong cùng lượt — không có gì để retry ở đây, lần sync
                // kế tiếp mà bài đó LẠI được push (vd sửa nội dung lần nữa)
                // sẽ tự thử lại. Nếu bài không đổi gì thêm thì sẽ không bao
                // giờ được thử lại qua đường này — chấp nhận được vì đây chỉ
                // là "tín hiệu nhanh"; nếu cần chắc chắn hơn, có thể bổ sung
                // cơ chế quét định kỳ ở bước sau.
                Log.e(TAG, "onReadingsSynced: lỗi xin tổng hợp reading_id=${reading.readingId}", e)
            }
        }
    }

    // ── MỚI — Gọi khi người dùng ĐỔI GIỌNG ngay trong 1 bài MyReading đang
    // mở (xem TtsVoicePickerScreen.onVoiceSelected, truyền readingId của
    // bài đang đọc). CHỈ xin server cho ĐÚNG bài này với giọng MỚI vừa
    // chọn, KHÔNG quét toàn bộ danh sách MyReading trong máy.
    //
    // ⚠️ KHÔNG yêu cầu điều kiện (b) "vừa push xong" như onReadingsSynced()
    // — ở đây mục đích khác hẳn: "giọng MỚI người dùng vừa chọn có thể CHƯA
    // TỪNG được xin cho bài này", không phải "phát hiện sự kiện vừa sync".
    // Vẫn giữ điều kiện (a) isAiProcessed == true, và vẫn tái dùng NGUYÊN
    // logic dedup của onReadingsSynced() (hasSentSameContent theo sid) —
    // nên gọi lặp lại (vd bấm đổi qua đổi lại) không tạo request thừa.
    //
    // Nếu readingId không phải bài MyReading (vd bài hệ thống) —
    // getReadingForSync() trả về null — hàm tự no-op, an toàn gọi từ mọi
    // màn đọc mà không cần caller tự kiểm tra trước "đây có phải MyReading
    // không".
    //
    // KHÔNG throw ra ngoài — cùng nguyên tắc "lưới an toàn tuỳ chọn" của
    // toàn bộ file này.
    suspend fun onVoiceChangedForReading(context: Context, readingId: String) {
        val vendor = TtsVoiceSnapshot.currentVendor()
        if (vendor != TtsVendor.KOKORO) return

        try {
            val repo = MyReadingRepository.getInstance(context)
            // ⚠️ GIẢ ĐỊNH chữ ký hàm dựa theo cách dùng ở
            // MyReadingAiProcessor.kt: getReadingForSync(readingId) trả về
            // Pair<Reading, *>?  — nếu MyReadingRepository.kt thật có chữ ký
            // khác, sửa lại đúng dòng này.
            val reading = repo.getReadingForSync(readingId)?.first
            if (reading == null) {
                Log.d(TAG, "onVoiceChangedForReading: reading_id=$readingId không phải bài MyReading, bỏ qua")
                return
            }
            if (!reading.isAiProcessed) {
                Log.d(TAG, "onVoiceChangedForReading: reading_id=$readingId chưa AI xử lý xong, bỏ qua")
                return
            }

            Log.d(
                TAG,
                "onVoiceChangedForReading: reading_id=$readingId giọng mới sid=${TtsVoiceSnapshot.currentSid()}"
            )
            onReadingsSynced(context, listOf(reading))
        } catch (e: Exception) {
            Log.e(TAG, "onVoiceChangedForReading: lỗi khi xin TTS cho reading_id=$readingId", e)
        }
    }

    // ── MỚI — Hỏi lại status định kỳ TRONG ÍT PHÚT, chạy NGẦM trên
    // pollScope (launch từ onReadingsSynced, KHÔNG suspend trực tiếp trong
    // vòng lặp for) — cầu nối cho khoảng trống giữa lúc gửi request (server
    // trả pending/processing) và lúc server thật sự xong (thường vài chục
    // giây tới vài phút với 1 bài ngắn). Áp dụng cho MỌI job pending/
    // processing, bất kể phát sinh từ đồng bộ hàng loạt (bài MyReading MỚI
    // vừa AI xử lý xong) hay từ đổi giọng tương tác (onVoiceChangedForReading).
    //
    // Không có polling này, app CHỈ biết server đã xong khi: (a)
    // TtsMyReadingPrecacheWorker chạy chu kỳ tiếp theo (tối đa 15 phút sau),
    // hoặc (b) người dùng thoát ra rồi mở lại bài (làm ReadingScreen tạo
    // mới, LaunchedEffect chạy lại, Gate hỏi lại) — cả 2 đều chậm/khó chịu
    // hơn nhiều so với việc tự hỏi lại ngay trong lúc người dùng vẫn đang ở
    // màn hình hoặc app đang chạy nền. Đây đúng là hành vi người dùng gặp
    // phải trong log thực tế: request lúc X, server xong lúc X+30~50s,
    // nhưng app chỉ biết khi mở lại bài vì không có gì tự hỏi lại trong lúc đó.
    //
    // Giới hạn tổng thời gian polling (MAX_POLL_ATTEMPTS * POLL_INTERVAL_MS)
    // — KHÔNG polling vô thời hạn: nếu vượt quá, dừng lặng lẽ, để lại entry
    // trong TtsMyReadingPendingStore cho TtsMyReadingPrecacheWorker tiếp
    // tục theo dõi ở các chu kỳ sau — không mất gì, chỉ là không còn "nhanh"
    // nữa.
    //
    // ⚠️ MỚI — activelyPolling.add()/remove(): đánh dấu "đang có luồng poll
    // xử lý job này" trong suốt thời gian hàm chạy — TtsMyReadingDownloadGate
    // dựa vào cờ này để biết mà KHÔNG tự gọi checkStatus() trùng lặp (xem
    // isPolling() + ghi chú ở đầu file). add() trả về false nếu key đã tồn
    // tại (đã có luồng khác đang poll đúng job này) — return sớm, tránh 2
    // luồng poll cùng chạy song song cho cùng 1 job (hiếm khi xảy ra vì
    // onReadingsSynced() chỉ launch poll đúng 1 lần mỗi lượt gọi request
    // thành công, nhưng phòng hờ nếu có đường gọi trùng nào khác trong
    // tương lai). `finally` đảm bảo LUÔN remove() dù hàm thoát ở return nào
    // (ready/failed/hết lượt poll) — không để sót cờ "đang poll" treo mãi.
    //
    // KHÔNG throw ra ngoài — cùng nguyên tắc "lưới an toàn tuỳ chọn" của
    // toàn bộ file này; 1 lần checkStatus() lỗi mạng giữa chừng chỉ bỏ qua
    // lượt đó, thử lại ở lượt polling kế tiếp.
    private suspend fun pollUntilReadyThenDownload(
        context: Context,
        client: TtsMyReadingRequestClient,
        readingId: String,
        sid: Int,
        contentHash: String,
    ) {
        val key = pollKey(readingId, sid)
        if (!activelyPolling.add(key)) {
            // Đã có 1 luồng poll khác đang xử lý đúng (readingId, sid) này
            // rồi — không cần chạy thêm 1 bản nữa, tránh 2 luồng cùng hỏi
            // server song song vô ích.
            Log.d(TAG, "pollUntilReadyThenDownload: reading_id=$readingId sid=$sid đã có luồng poll khác đang chạy, bỏ qua")
            return
        }

        try {
            repeat(MAX_POLL_ATTEMPTS) { attempt ->
                delay(POLL_INTERVAL_MS)

                val status = try {
                    client.checkStatus(readingId = readingId, sid = sid, contentHash = contentHash)
                } catch (e: Exception) {
                    Log.e(TAG, "pollUntilReadyThenDownload: lỗi checkStatus reading_id=$readingId sid=$sid (lần ${attempt + 1})", e)
                    null
                }

                when (status) {
                    TtsMyReadingJobStatus.READY -> {
                        Log.d(
                            TAG,
                            "pollUntilReadyThenDownload: reading_id=$readingId sid=$sid READY sau ${attempt + 1} lần hỏi, enqueue tải Drive"
                        )
                        TtsKokoroPackScheduler.enqueueDownload(context, readingId, sid)
                        TtsMyReadingPendingStore.remove(readingId, sid)
                        return
                    }
                    TtsMyReadingJobStatus.FAILED -> {
                        Log.w(TAG, "pollUntilReadyThenDownload: reading_id=$readingId sid=$sid server báo FAILED, dừng polling")
                        TtsMyReadingPendingStore.remove(readingId, sid)
                        TtsMyReadingSentRequestStore.remove(readingId, sid)
                        return
                    }
                    else -> {
                        // PENDING/PROCESSING/UNKNOWN/null — thử lại ở lượt kế
                        // tiếp, không log ồn ào mỗi lượt bình thường.
                    }
                }
            }

            Log.d(
                TAG,
                "pollUntilReadyThenDownload: reading_id=$readingId sid=$sid chưa READY sau $MAX_POLL_ATTEMPTS lần hỏi, " +
                        "để TtsMyReadingPrecacheWorker tiếp tục theo dõi ở chu kỳ sau"
            )
        } finally {
            activelyPolling.remove(key)
        }
    }

    // ── Build TOÀN BỘ items (sentence + phrase + word) cần server tổng hợp
    // giọng, từ ĐÚNG danh sách sentences dùng để tính contentHash — đảm bảo
    // 2 lần gọi cùng nội dung luôn ra cùng contentHash VÀ cùng items, không
    // có nguy cơ lệch pha giữa 2 nguồn dữ liệu khác nhau.
    //
    // Duyệt theo sentenceOrder (giống TtsMyReadingContentHash.compute()) để
    // thứ tự items ổn định, dễ debug qua GET /debug/job-items — tuy server
    // không phụ thuộc thứ tự này để hoạt động đúng.
    //
    // Lọc bỏ mọi item có textEn null/rỗng — không có gì để server đọc, gửi
    // lên chỉ tốn băng thông và làm job processor (bước 8) phải tự lọc lại.
    private fun buildItems(sentences: List<ReadingSentence>): List<TtsMyReadingItem> {
        val items = mutableListOf<TtsMyReadingItem>()

        sentences.sortedBy { it.sentenceOrder }.forEach { sentence ->
            sentence.textEn?.takeIf { it.isNotBlank() }?.let { text ->
                items += TtsMyReadingItem(
                    type    = TtsMyReadingItemType.SENTENCE,
                    itemId  = sentence.sentenceId,
                    textEn  = text,
                )
            }

            sentence.phrases.forEach { phrase ->
                phrase.textEn?.takeIf { it.isNotBlank() }?.let { text ->
                    items += TtsMyReadingItem(
                        type    = TtsMyReadingItemType.PHRASE,
                        itemId  = phrase.phraseId,
                        textEn  = text,
                    )
                }
            }

            sentence.words.forEach { word ->
                word.textEn?.takeIf { it.isNotBlank() }?.let { text ->
                    items += TtsMyReadingItem(
                        type    = TtsMyReadingItemType.WORD,
                        itemId  = word.wordId,
                        textEn  = text,
                    )
                }
            }
        }

        return items
    }
}