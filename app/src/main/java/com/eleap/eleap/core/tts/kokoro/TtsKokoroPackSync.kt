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
// ⚠️ 3 thành phần trong file này, đọc theo đúng luồng chạy thực tế:
//   1. TtsKokoroPackSourceRegistry — giữ transport (Drive/...) đang active
//   2. TtsKokoroPackScheduler      — enqueue OneTimeWork cho (readingId, sid)
//   3. TtsKokoroPackWorker         — WorkManager chạy tới, tra Registry rồi
//                                     gọi TtsKokoroPackDownloader.syncIfNeeded()
package com.eleap.eleap.core.tts.kokoro

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf

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

    // TtsKokoroPackWorker gọi hàm này mỗi khi cần — trả về null nếu chưa
    // từng register(), caller tự hiểu là "chưa cấu hình transport nào cho
    // Kokoro".
    fun current(): TtsKokoroPackSource? = source
}

// ═══════════════════════════════════════════════════════════════════════════
// 2. SCHEDULER — nơi DUY NHẤT gọi WorkManager để enqueue TtsKokoroPackWorker
// ═══════════════════════════════════════════════════════════════════════════
//
// Mỗi (readingId, sid) có 1 tên unique work RIÊNG (không phải 1 tên duy
// nhất cho toàn app) — vì đây là việc tải 1 gói CỤ THỂ, nhiều gói khác nhau
// có thể cần tải song song (vd người dùng mở nhanh 2 bài khác nhau), không
// nên việc tải bài A chặn mất việc tải bài B.
//
// ⚠️ CHỈ DÙNG CHO GIỌNG KOKORO — nếu người dùng chọn giọng của nhà cung cấp
// khác (vd Google Cloud TTS on-demand, không cần đồng bộ), nơi gọi (vd
// TtsVoicePickerScreen) sẽ KHÔNG gọi tới scheduler này — mỗi nhà cung cấp
// tự quyết định có cần enqueue việc gì hay không, không đi qua đây.
//
// CÓ networkConstraint — vì đây là việc BẮT BUỘC phải có mạng, enqueue mà
// chưa có mạng thì WorkManager tự giữ lại, tự chạy ngay khi có mạng trở
// lại, không cần tự viết logic chờ mạng.
//
// Singleton thủ công, KHÔNG dùng Hilt/DI.
object TtsKokoroPackScheduler {

    private const val UNIQUE_WORK_PREFIX = "tts_kokoro_pack_"

    private fun uniqueWorkName(readingId: String, sid: Int) = "$UNIQUE_WORK_PREFIX${readingId}_$sid"

    // ── Enqueue 1 lượt tải cho ĐÚNG (readingId, sid) — gọi ngay khi người
    // dùng mở 1 bài đọc VÀ đang dùng giọng Kokoro (biết ngay readingId + sid
    // đang chọn). KEEP — nếu đã có lượt tải đang chạy/đang chờ mạng cho
    // ĐÚNG cặp này, không tạo bản sao chạy song song; nếu lượt trước đã
    // XONG (thành công hay thất bại đều là "xong"), KEEP vẫn cho enqueue
    // lại bình thường.
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
}

// ═══════════════════════════════════════════════════════════════════════════
// 3. WORKER — CoroutineWorker chạy nền, xử lý ĐÚNG 1 (readingId, sid) mỗi
//    lần chạy
// ═══════════════════════════════════════════════════════════════════════════
//
// Chỉ cần tải ĐÚNG bài/giọng người dùng đang mở NGAY LÚC NÀY. Mỗi lần mở 1
// bài khác/đổi giọng khác, enqueue 1 Worker mới cho đúng cặp đó (xem
// TtsKokoroPackScheduler ở trên).
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