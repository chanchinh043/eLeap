// TtsKokoroPackSync.kt
// Đặt tại: com/eleap/eleap/core/tts/kokoro/TtsKokoroPackSync.kt
// (gộp 3 file trước đó: TtsKokoroPackSourceRegistry.kt,
// TtsKokoroPackScheduler.kt, TtsKokoroPackWorker.kt — logic của cả 3 giữ
// nguyên 100%, không đổi gì bên trong. Gộp vì cả 3 cùng phục vụ đúng 1 mối
// quan tâm duy nhất — "giữ cache Kokoro đồng bộ qua WorkManager" — và phụ
// thuộc chặt vào nhau (Worker cần đúng key của chính nó, Scheduler cần
// đúng class Worker, Registry chỉ được Worker đọc) — tách riêng 3 file nhỏ
// (~30-60 dòng mỗi file) không giúp gì thêm cho việc điều hướng code, chỉ
// khiến phải nhảy qua lại giữa 3 tab khi đọc luồng "enqueue → worker chạy →
// tra registry → gọi downloader".
//
// ⚠️ THÊM MỚI: TtsKokoroReadingSyncWorker — xử lý "tải TOÀN BỘ giọng của 1
// bài" trong ĐÚNG 1 lần chạy Worker, gọi
// TtsKokoroPackDownloader.syncAllVoicesForReading() — hàm đó tự
// fetchManifest(readingId) CHỈ 1 LẦN rồi tải tuần tự từng giọng có trên
// Drive (xem TtsKokoroPackDownloader.kt). KHÁC với cách cũ (đã bỏ) là
// chain nhiều TtsKokoroPackWorker riêng lẻ theo (readingId, sid) — cách đó
// tuy cũng tải tuần tự nhưng MỖI bước trong chain lại tự fetchManifest()
// riêng, dù kết quả giống hệt nhau cho cùng 1 readingId → tốn N lần gọi
// Drive API một cách vô ích cho N giọng của cùng 1 bài.
//
// ⚠️ 4 thành phần trong file này, đọc theo đúng luồng chạy thực tế:
//   1. TtsKokoroPackSourceRegistry — giữ transport (Drive/...) đang active
//   2. TtsKokoroPackScheduler      — enqueue OneTimeWork (theo sid lẻ HOẶC
//                                     theo cả bài)
//   3. TtsKokoroPackWorker         — xử lý ĐÚNG 1 (readingId, sid), dùng
//                                     khi chỉ cần tải/làm mới 1 giọng
//   4. TtsKokoroReadingSyncWorker  — xử lý TOÀN BỘ giọng của 1 bài trong 1
//                                     lần chạy, quét Drive đúng 1 lần
package com.eleap.eleap.core.tts.kokoro

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.eleap.eleap.core.tts.TtsVendor
import com.eleap.eleap.core.tts.TtsVoiceCatalog
import java.util.concurrent.TimeUnit

private const val TAG = "TtsKokoroPackSync"

// ═══════════════════════════════════════════════════════════════════════════
// 1. REGISTRY — chỗ DUY NHẤT giữ tham chiếu tới TtsKokoroPackSource
//    (transport) đang được cấu hình cho Kokoro.
// ═══════════════════════════════════════════════════════════════════════════
//
// Nơi khởi tạo app (MainActivity.onCreate(), qua TtsKokoroConfig) sẽ gọi
// register(...) đúng 1 lần với impl cụ thể đã chọn.
//
// ⚠️ PHẠM VI: registry này CHỈ giữ 1 transport CHO KOKORO (vd Drive, hoặc
// sau này đổi sang S3) — KHÔNG phải registry chung cho MỌI nhà cung cấp
// trong app. Nhà cung cấp khác (google_cloud/...) không đăng ký gì vào đây
// — nếu nó cần 1 cơ chế tương tự, nó tự có registry riêng trong thư mục của
// nó, với contract riêng phù hợp với cách nó hoạt động. MỖI vendor tự quản
// lý transport của MÌNH, không có 1 registry trung tâm cố "biết hết" mọi
// vendor.
//
// source == null nghĩa là CHƯA cấu hình transport nào cho Kokoro — mọi nơi
// gọi tới đây phải tự coi đây là tình huống BÌNH THƯỜNG, không phải lỗi:
// đơn giản là "không có gì để tải". ⚠️ LƯU Ý: nếu source == null thì audio
// pre-cache của Kokoro sẽ KHÔNG BAO GIỜ có — TtsPlaybackRouter sẽ luôn
// fallback sang Android TTS hệ thống cho mọi lượt phát dùng giọng Kokoro.
// Đây là lý do TtsKokoroConfig.registerIfConfigured() BẮT BUỘC phải chạy
// đúng, không được bỏ sót ở MainActivity.
//
// Singleton thủ công, KHÔNG dùng Hilt/DI — cùng phong cách với TtsManager.
object TtsKokoroPackSourceRegistry {

    @Volatile
    private var source: TtsKokoroPackSource? = null

    // Gọi 1 lần lúc khởi tạo app, sau khi đã chọn xong impl cụ thể (vd
    // TtsGoogleDriveSource). An toàn gọi lại nhiều lần — ghi đè đơn giản,
    // không có tác dụng phụ.
    fun register(newSource: TtsKokoroPackSource) {
        source = newSource
    }

    // TtsKokoroPackWorker/TtsKokoroReadingSyncWorker gọi hàm này mỗi khi
    // cần — trả về null nếu chưa từng register(), caller tự hiểu là "chưa
    // cấu hình transport nào cho Kokoro".
    fun current(): TtsKokoroPackSource? = source
}

// ═══════════════════════════════════════════════════════════════════════════
// 2. SCHEDULER — nơi DUY NHẤT gọi WorkManager để enqueue Worker của Kokoro
// ═══════════════════════════════════════════════════════════════════════════
//
// ⚠️ CHỈ DÙNG CHO GIỌNG KOKORO — nếu người dùng chọn giọng của nhà cung cấp
// khác (vd Google Cloud TTS on-demand, không cần đồng bộ), nơi gọi (vd
// ReadingScreen/TtsVoicePickerScreen) sẽ KHÔNG gọi tới scheduler này — mỗi
// nhà cung cấp tự quyết định có cần enqueue việc gì hay không, không đi qua
// đây.
//
// CÓ networkConstraint — vì đây là việc BẮT BUỘC phải có mạng, enqueue mà
// chưa có mạng thì WorkManager tự giữ lại, tự chạy ngay khi có mạng trở
// lại, không cần tự viết logic chờ mạng.
//
// Singleton thủ công, KHÔNG dùng Hilt/DI.
object TtsKokoroPackScheduler {

    private const val UNIQUE_WORK_PREFIX = "tts_kokoro_pack_"

    private fun uniqueWorkName(readingId: String, sid: Int) = "$UNIQUE_WORK_PREFIX${readingId}_$sid"

    private fun uniqueReadingWorkName(readingId: String) = "${UNIQUE_WORK_PREFIX}reading_$readingId"

    // ── Enqueue 1 lượt tải cho ĐÚNG (readingId, sid) — dùng khi CHỈ cần
    // đúng 1 giọng cụ thể (vd TtsVoicePickerScreen muốn tải NGAY giọng
    // người dùng vừa chọn, ưu tiên hơn lô đang chạy nền qua
    // enqueueDownloadAllVoices() bên dưới, vì đây là unique work TÊN RIÊNG,
    // chạy song song độc lập). KEEP — nếu đã có lượt tải đang chạy/đang chờ
    // mạng cho ĐÚNG cặp này, không tạo bản sao chạy song song; nếu lượt
    // trước đã XONG (thành công hay thất bại đều là "xong"), KEEP vẫn cho
    // enqueue lại bình thường.
    fun enqueueDownload(context: Context, readingId: String, sid: Int) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val inputData = workDataOf(
            TtsKokoroPackWorker.KEY_READING_ID to readingId,
            TtsKokoroPackWorker.KEY_SID to sid,
        )

        val request = OneTimeWorkRequestBuilder<TtsKokoroPackWorker>()
            .setConstraints(constraints)
            .setInputData(inputData)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            uniqueWorkName(readingId, sid),
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    // ── Enqueue tải TOÀN BỘ giọng Kokoro (tiếng Anh) của 1 bài trong ĐÚNG 1
    // lượt Worker — gọi khi người dùng MỞ bài đó — thay cho việc chỉ tải
    // đúng 1 giọng đang chọn như enqueueDownload() ở trên.
    //
    // Thứ tự tải BÊN TRONG Worker (xem TtsKokoroReadingSyncWorker.doWork()
    // → TtsKokoroPackDownloader.syncAllVoicesForReading()):
    //   1. Giọng đang chọn (selectedSid) — tải TRƯỚC, để có audio đúng giọng
    //      người dùng sắp nghe sớm nhất có thể.
    //   2. Các giọng tiếng Anh còn lại của Kokoro — tải SAU, theo thứ tự bất
    //      kỳ (thứ tự khai báo trong TtsKokoroVoices), tuần tự từng gói một
    //      (không song song, tránh nghẽn băng thông đúng lúc cần nghe giọng
    //      đầu tiên).
    //
    // ⚠️ CHỈ 1 LẦN GỌI DRIVE cho toàn bộ N giọng — TOÀN BỘ việc quét
    // (fetchManifest) VÀ tải tuần tự đều nằm gọn trong 1 lượt doWork() của
    // TtsKokoroReadingSyncWorker, KHÔNG chain nhiều Worker riêng theo từng
    // sid (khác thiết kế cũ) — mỗi Worker con trong chain sẽ tự
    // fetchManifest() lại một cách dư thừa dù kết quả giống hệt nhau cho
    // cùng 1 readingId.
    //
    // ⚠️ CHỈ giọng TIẾNG ANH (TtsVoiceCatalog.englishVoices) — đúng phạm vi
    // mà TtsVoicePickerScreen cho người dùng chọn (eLeap chỉ dạy tiếng Anh),
    // KHÔNG tải cả 53 giọng đa ngôn ngữ của Kokoro.
    //
    // KEEP — nếu bài này đã có 1 lượt sync đang chạy/đang chờ mạng, không
    // tạo thêm lượt song song; nếu lượt trước đã xong (dù thành hay bại),
    // enqueue lại bình thường (vd người dùng rời bài rồi quay lại sau).
    fun enqueueDownloadAllVoices(context: Context, readingId: String, selectedSid: Int) {
        val allSids = TtsVoiceCatalog.englishVoices
            .filter { it.vendor == TtsVendor.KOKORO }
            .map { it.sid }
            .distinct()

        if (allSids.isEmpty()) {
            Log.w(TAG, "enqueueDownloadAllVoices: danh mục giọng Kokoro tiếng Anh rỗng, bỏ qua reading=$readingId")
            return
        }

        // Giọng đang chọn lên ĐẦU danh sách, các giọng còn lại giữ nguyên
        // thứ tự khai báo — dùng distinct() để phòng trường hợp selectedSid
        // không hề nằm trong allSids (vd prefs cũ trỏ tới 1 sid đã bị xoá
        // khỏi danh mục), khi đó vẫn tải đủ allSids, chỉ là không có bước
        // "ưu tiên" nào thực sự xảy ra.
        val orderedSids = (listOf(selectedSid) + allSids).distinct()

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val inputData = workDataOf(
            TtsKokoroReadingSyncWorker.KEY_READING_ID to readingId,
            TtsKokoroReadingSyncWorker.KEY_ORDERED_SIDS to orderedSids.toIntArray(),
        )

        val request = OneTimeWorkRequestBuilder<TtsKokoroReadingSyncWorker>()
            .setConstraints(constraints)
            .setInputData(inputData)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            uniqueReadingWorkName(readingId),
            ExistingWorkPolicy.KEEP,
            request,
        )

        Log.d(
            TAG,
            "enqueueDownloadAllVoices: đã enqueue 1 Worker cho reading=$readingId, " +
                    "${orderedSids.size} giọng, thứ tự ưu tiên=$orderedSids"
        )
    }

    // ── Enqueue lượt "tải ĐỦ toàn bộ giọng Kokoro (tiếng Anh) cho bài này,
    // retry tới khi xong" — ĐIỂM GỌI DÙNG KHI NGƯỜI DÙNG MỞ 1 BÀI ĐỌC. Khác
    // enqueueDownloadAllVoices() ở trên (dùng cờ per-sid có hạn 24h, phù hợp
    // cho việc DÒ BẢN MỚI định kỳ) — hàm này dùng
    // TtsKokoroReadingEnsureSyncWorker, có Result.retry() với backoff, nhắm
    // đúng mục tiêu "tải cho bằng được lần đầu", KHÔNG quan tâm 24h.
    //
    // ⚠️ Cùng 1 CHỮ ký tham số như enqueueDownloadAllVoices() nhưng dùng
    // UNIQUE WORK NAME KHÁC (hậu tố "_ensure") — 2 lượt work có thể tồn tại
    // song song mà không đụng nhau, dù trong thực tế ReadingScreen hiện chỉ
    // gọi ĐÚNG 1 trong 2 (xem ghi chú ở ReadingScreen.kt).
    //
    // KEEP — nếu bài này đã có 1 lượt "ensure" đang chạy/đang retry/đang chờ
    // mạng, không tạo bản sao chạy song song; nếu lượt trước đã XONG (dù đã
    // tải đủ hay đã bỏ cuộc sau khi hết số lần retry cho phép — xem
    // TtsKokoroReadingEnsureSyncWorker.MAX_ATTEMPTS), enqueue lại bình
    // thường (vd người dùng rời bài rồi quay lại sau, cho 1 cơ hội thử lại
    // mới).
    fun enqueueEnsureReadingSynced(context: Context, readingId: String, selectedSid: Int) {
        val allSids = TtsVoiceCatalog.englishVoices
            .filter { it.vendor == TtsVendor.KOKORO }
            .map { it.sid }
            .distinct()

        if (allSids.isEmpty()) {
            Log.w(TAG, "enqueueEnsureReadingSynced: danh mục giọng Kokoro tiếng Anh rỗng, bỏ qua reading=$readingId")
            return
        }

        val orderedSids = (listOf(selectedSid) + allSids).distinct()

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val inputData = workDataOf(
            TtsKokoroReadingEnsureSyncWorker.KEY_READING_ID to readingId,
            TtsKokoroReadingEnsureSyncWorker.KEY_ORDERED_SIDS to orderedSids.toIntArray(),
        )

        val request = OneTimeWorkRequestBuilder<TtsKokoroReadingEnsureSyncWorker>()
            .setConstraints(constraints)
            .setInputData(inputData)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WORK_BACKOFF_MIN_MS, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "${UNIQUE_WORK_PREFIX}ensure_$readingId",
            ExistingWorkPolicy.KEEP,
            request,
        )

        Log.d(
            TAG,
            "enqueueEnsureReadingSynced: đã enqueue 1 Worker (có retry) cho reading=$readingId, " +
                    "${orderedSids.size} giọng, thứ tự ưu tiên=$orderedSids"
        )
    }

    // WorkManager yêu cầu backoff tối thiểu 10s — dùng hằng số riêng để rõ
    // ràng, tránh magic number lẫn trong lời gọi setBackoffCriteria().
    private const val WORK_BACKOFF_MIN_MS = 10_000L
}

// ═══════════════════════════════════════════════════════════════════════════
// 3. WORKER (1 giọng) — CoroutineWorker chạy nền, xử lý ĐÚNG 1
//    (readingId, sid) mỗi lần chạy
// ═══════════════════════════════════════════════════════════════════════════
//
// Dùng khi CHỈ cần tải/làm mới đúng 1 giọng cụ thể (xem
// TtsKokoroPackScheduler.enqueueDownload()). Muốn tải CẢ BÀI (mọi giọng
// tiếng Anh), dùng TtsKokoroReadingSyncWorker bên dưới thay vì enqueue
// nhiều Worker này liên tiếp — tránh fetchManifest() lặp lại vô ích.
//
// ⚠️ ĐÂY LÀ NƠI TRA TtsKokoroPackSourceRegistry — TtsKokoroPackDownloader
// CHỦ ĐỘNG KHÔNG tự tra registry bên trong (để dễ test, tường minh hơn),
// nên Worker này chính là nơi "nối dây" giữa Registry và Downloader: tra
// transport hiện tại, nếu có thì mới gọi
// TtsKokoroPackDownloader.syncIfNeeded(source, ...).
//
// KHÔNG retry nhiều lần nếu thất bại — mất mạng/server lỗi là tình huống
// BÌNH THƯỜNG. Vì vậy luôn trả Result.success() dù tải được hay không —
// Result.failure()/retry() chỉ dành cho lỗi THỰC SỰ bất thường (không áp
// dụng ở đây, mọi nhánh thất bại đều đã được coi là "bình thường" ngay
// trong TtsKokoroPackDownloader).
//
// ⚠️ QUAN TRỌNG: khác thiết kế cũ khi còn Kokoro tự sinh on-device — hiện
// KHÔNG có worker pregen/ nào tự sinh audio làm lưới an toàn nếu Worker này
// thất bại. Nếu tải lỗi, audio đơn giản là chưa có, TtsPlaybackRouter sẽ
// fallback Android TTS cho tới khi 1 lượt tải sau đó (mở lại bài, hoặc có
// mạng trở lại) thành công.
class TtsKokoroPackWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_READING_ID = "reading_id"
        const val KEY_SID = "sid"
    }

    override suspend fun doWork(): Result {
        val readingId = inputData.getString(KEY_READING_ID)
        val sid = inputData.getInt(KEY_SID, -1)

        if (readingId.isNullOrBlank() || sid < 0) {
            Log.w(TAG, "doWork: thiếu readingId/sid hợp lệ, bỏ qua")
            return Result.success()
        }

        // ── Tra transport hiện tại của Kokoro (vd Drive) — null nghĩa là
        // CHƯA cấu hình (xem TtsKokoroConfig.registerIfConfigured()), coi
        // như không có gì để đồng bộ, KHÔNG phải lỗi. ───────────────────────
        val source = TtsKokoroPackSourceRegistry.current()
        if (source == null) {
            Log.d(TAG, "doWork: chưa cấu hình transport cho Kokoro, bỏ qua reading=$readingId sid=$sid")
            return Result.success()
        }

        // Gọi qua syncIfNeeded() — có gate 24h, tự tránh tải lại nguyên gói
        // .zip mỗi lần người dùng mở lại 1 bài đã có cache local từ trước,
        // dù nội dung trên Drive không hề đổi.
        val ok = TtsKokoroPackDownloader.syncIfNeeded(applicationContext, source, readingId, sid)
        Log.d(TAG, "doWork: reading=$readingId sid=$sid kết quả đồng bộ=$ok")
        return Result.success()
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// 4. WORKER (cả bài) — CoroutineWorker chạy nền, xử lý TOÀN BỘ giọng của 1
//    bài trong ĐÚNG 1 lần chạy
// ═══════════════════════════════════════════════════════════════════════════
//
// Nhận vào readingId + danh sách sid theo THỨ TỰ ƯU TIÊN (phần tử đầu =
// giọng đang chọn) — gọi ĐÚNG 1 hàm
// TtsKokoroPackDownloader.syncAllVoicesForReading(), hàm đó tự
// fetchManifest(readingId) CHỈ 1 LẦN rồi tải tuần tự từng giọng có mặt
// trên Drive theo đúng thứ tự đã truyền vào — xem TtsKokoroPackDownloader.kt.
//
// ⚠️ VÌ SAO 1 WORKER DUY NHẤT (không chain nhiều Worker như thiết kế cũ):
// toàn bộ N giọng của CÙNG 1 bài đều cần đúng 1 thông tin (kết quả
// fetchManifest(readingId)) — gộp vào 1 lần doWork() cho phép tái dùng
// đúng 1 lần gọi Drive cho cả N giọng, thay vì mỗi Worker con trong chain
// tự gọi lại. Việc tải tuần tự (không song song) vẫn được đảm bảo tự nhiên
// vì cả vòng lặp nằm trong 1 hàm suspend duy nhất — không cần WorkManager
// chain để ép thứ tự.
//
// inputData dùng IntArray cho danh sách sid (workDataOf/Data hỗ trợ sẵn
// kiểu này, không cần tự serialize JSON).
class TtsKokoroReadingSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_READING_ID = "reading_id"
        const val KEY_ORDERED_SIDS = "ordered_sids"
    }

    override suspend fun doWork(): Result {
        val readingId = inputData.getString(KEY_READING_ID)
        val orderedSids = inputData.getIntArray(KEY_ORDERED_SIDS)?.toList()

        if (readingId.isNullOrBlank() || orderedSids.isNullOrEmpty()) {
            Log.w(TAG, "doWork(reading): thiếu readingId/orderedSids hợp lệ, bỏ qua")
            return Result.success()
        }

        val source = TtsKokoroPackSourceRegistry.current()
        if (source == null) {
            Log.d(TAG, "doWork(reading): chưa cấu hình transport cho Kokoro, bỏ qua reading=$readingId")
            return Result.success()
        }

        val ok = TtsKokoroPackDownloader.syncAllVoicesForReading(applicationContext, source, readingId, orderedSids)
        Log.d(TAG, "doWork(reading): reading=$readingId (${orderedSids.size} giọng) kết quả=$ok")
        return Result.success()
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// 5. WORKER (tải đủ lần đầu, RETRY tới khi xong) — dùng khi MỞ 1 bài đọc,
//    KHÁC 3 Worker ở trên (tất cả đều luôn Result.success(), coi mất mạng/
//    lỗi là bình thường, để lượt MỞ BÀI SAU tự thử lại). Worker này chủ động
//    Result.retry() khi CHƯA tải đủ, để WorkManager tự chạy lại (có backoff,
//    tự chờ tới khi có mạng) MÀ KHÔNG cần người dùng tự mở lại bài — đúng
//    yêu cầu "ngầm tải, có thể retry tới khi tải xong tất cả giọng hiện có".
// ═══════════════════════════════════════════════════════════════════════════
//
// Gọi TtsKokoroPackDownloader.ensureReadingFullySynced() — hàm đó tự có
// cổng "đã tải đủ" ở bước đầu (không gọi mạng nếu đã xong), nên gọi lại
// Worker này nhiều lần (kể cả sau khi đã xong) là AN TOÀN và RẺ.
//
// ⚠️ MAX_ATTEMPTS: chặn trên để tránh retry vô hạn tốn pin nếu rơi vào tình
// huống bất thường kéo dài (vd server luôn lỗi cho đúng 1 giọng). Hết
// MAX_ATTEMPTS mà vẫn chưa xong → Result.success() (KHÔNG phải retry() hay
// failure()) để WorkManager coi lượt này là "đã xong" (không tự retry nữa),
// nhưng vẫn để ExistingWorkPolicy.KEEP cho phép lượt MỞ BÀI TIẾP THEO tự
// enqueue lại, cho thêm 1 loạt cơ hội mới — không bao giờ "khoá cứng" bài
// đọc ở trạng thái chưa tải đủ vĩnh viễn.
class TtsKokoroReadingEnsureSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_READING_ID = "reading_id"
        const val KEY_ORDERED_SIDS = "ordered_sids"
        private const val MAX_ATTEMPTS = 20
    }

    override suspend fun doWork(): Result {
        val readingId = inputData.getString(KEY_READING_ID)
        val orderedSids = inputData.getIntArray(KEY_ORDERED_SIDS)?.toList()

        if (readingId.isNullOrBlank() || orderedSids.isNullOrEmpty()) {
            Log.w(TAG, "doWork(ensure): thiếu readingId/orderedSids hợp lệ, bỏ qua")
            return Result.success()
        }

        val source = TtsKokoroPackSourceRegistry.current()
        if (source == null) {
            Log.d(TAG, "doWork(ensure): chưa cấu hình transport cho Kokoro, bỏ qua reading=$readingId")
            return Result.success()
        }

        val ok = TtsKokoroPackDownloader.ensureReadingFullySynced(applicationContext, source, readingId, orderedSids)
        if (ok) {
            Log.d(TAG, "doWork(ensure): reading=$readingId đã tải ĐỦ, dừng retry")
            return Result.success()
        }

        if (runAttemptCount >= MAX_ATTEMPTS) {
            Log.w(
                TAG,
                "doWork(ensure): reading=$readingId vẫn CHƯA đủ sau $runAttemptCount lần thử, tạm dừng " +
                        "(lần mở bài KẾ TIẾP sẽ tự enqueue lại, thử tiếp)"
            )
            return Result.success()
        }

        Log.d(TAG, "doWork(ensure): reading=$readingId CHƯA đủ giọng (lần thử #$runAttemptCount), sẽ retry")
        return Result.retry()
    }
}