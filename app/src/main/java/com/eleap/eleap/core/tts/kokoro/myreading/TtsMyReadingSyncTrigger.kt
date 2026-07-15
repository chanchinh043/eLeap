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
// ⚠️ ĐIỀU KIỆN KÍCH HOẠT — CẢ HAI phải đúng, thiếu 1 trong 2 đều KHÔNG gọi:
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
// ⚠️ CHỈ XIN CHO 1 GIỌNG — giọng người dùng ĐANG CHỌN hiện tại
// (TtsVoiceSnapshot.currentVendor()/currentSid()), KHÔNG xin sẵn toàn bộ 53
// giọng Kokoro cho mỗi bài. Lý do: nhu cầu thực tế là "khi mở bài, có giọng
// đang chọn để nghe", xin dư thừa mọi giọng vừa tốn tài nguyên server vừa
// không chắc người dùng bao giờ chọn tới. Nếu người dùng đổi giọng sau này,
// TtsVoicePickerScreen (ở bước sau, phần "trigger tải về phía App khi mở
// bài") sẽ tự xin thêm cho giọng mới — không cần lo trước ở đây.
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
// Hàm KHÔNG throw ra ngoài — lỗi ở đây (gọi server thất bại) KHÔNG được làm
// hỏng lượt sync readings/sentences đang chạy, đúng nguyên tắc "core/tts/
// luôn là lưới an toàn tuỳ chọn". MyReadingSyncEngine gọi hàm này "bắn rồi
// quên" (fire-and-forget), không chặn/không throw ngược lên.
package com.eleap.eleap.core.tts.kokoro.myreading

import android.content.Context
import android.util.Log
import com.eleap.eleap.core.tts.TtsVendor
import com.eleap.eleap.core.tts.TtsVoiceSnapshot
import com.eleap.eleap.feature.myreading.data.MyReadingRepository
import com.eleap.eleap.feature.reading.data.Reading
import com.eleap.eleap.feature.reading.data.ReadingSentence

private const val TAG = "TtsMyReadingSyncTrigger"

object TtsMyReadingSyncTrigger {

    // ── Điểm gọi CHÍNH — gọi từ MyReadingSyncEngine.pushPendingLocked() NGAY
    // SAU KHI biết succeededIds của lượt push này (readingId nào vừa
    // create/update thành công lên Supabase), HOẶC từ MyReadingAiProcessor
    // ngay sau khi AI xử lý xong cho GUEST (xem ghi chú ở đó). Nhận thẳng
    // danh sách Reading đã push thành công (không phải chỉ readingId) để đọc
    // isAiProcessed mà không cần query lại DB thêm 1 lần.
    //
    // context: dùng để lấy MyReadingRepository (đọc sentences build items +
    // tính hash) và đọc BuildConfig — LUÔN truyền applicationContext ở nơi gọi.
    suspend fun onReadingsSynced(context: Context, syncedReadings: List<Reading>) {
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

                if (status != null && status != TtsMyReadingJobStatus.READY) {
                    // Chưa ready ngay — ghi vào store để
                    // TtsMyReadingPrecacheWorker (chạy nền định kỳ) tự hỏi
                    // lại sau, không cần đợi user mở bài mới biết. Nếu
                    // status == READY ngay từ lần xin đầu (hiếm, vd server
                    // đã từng xử lý sẵn cho đúng contentHash này trước đó)
                    // thì KHÔNG cần ghi store — không có gì phải chờ nữa.
                    TtsMyReadingPendingStore.add(
                        TtsMyReadingPendingEntry(
                            readingId   = reading.readingId,
                            sid         = sid,
                            contentHash = contentHash,
                        )
                    )
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