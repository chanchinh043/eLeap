// TtsVoiceSnapshot.kt
// Đặt tại: com/eleap/eleap/core/tts/pregen/TtsVoiceSnapshot.kt
//
// Ghi nhớ "giọng Kokoro đang được chọn GẦN NHẤT" — lưu XUỐNG ĐĨA
// (SharedPreferences), cùng lý do như TtsReadingHistory: phải sống sót qua
// việc app bị kill hẳn rồi mở lại, để khi mở lại app, TtsPregenWorker biết
// ngay cần tiếp tục generate cho đúng giọng nào mà KHÔNG cần người dùng phải
// mở lại màn chọn giọng.
//
// CHỈ áp dụng cho Kokoro — Android TTS (giọng hệ thống) KHÔNG được pre-cache
// (xem lý do ở KokoroTtsEngine.kt/TtsManager.kt: Android TTS generate gần
// như tức thời, pre-cache cho nó chỉ tổ tốn dung lượng vô ích). Vì vậy
// "giọng mục tiêu để pre-cache" chỉ tồn tại khi TtsManager đang active đúng
// Kokoro; nếu đang là Android TTS, coi như không có giọng mục tiêu nào cả.
//
// ⚠️ Lưu thêm "thời điểm chọn" (không chỉ sid) — để TtsPregenWorker có thể
// so sánh và phát hiện đúng lúc người dùng VỪA đổi giọng giữa lúc Worker
// đang generate ngầm dở dang (xem mục 6d trong thiết kế: ngắt ngay, chuyển
// sang generate theo giọng mới, KHÔNG xoá phần đã generate theo giọng cũ vì
// cấu trúc thư mục cache đã tách riêng theo sid).
//
// ⚠️ MỚI (tích hợp cuối — điểm gọi (c) trong thiết kế TtsPregenScheduler):
// giữ lại applicationContext từ lần init() gần nhất, để recordSelectedSid()
// có thể TỰ enqueue TtsPregenWorker ngay khi giọng vừa đổi — không cần
// TtsManager.setKokoroSpeaker() (nơi gọi recordSelectedSid()) phải biết gì
// về WorkManager/TtsPregenScheduler, giữ đúng ranh giới: TtsManager chỉ lo
// việc đọc, TtsVoiceSnapshot lo việc "nhớ + báo cho Worker biết".
//
// Singleton thủ công, KHÔNG dùng Hilt/DI — cùng phong cách với
// TtsReadingHistory, TtsForegroundReading.
package com.eleap.eleap.core.tts.pregen

import android.content.Context
import android.content.SharedPreferences
import com.eleap.eleap.core.tts.TtsManager

object TtsVoiceSnapshot {

    private const val PREFS_NAME     = "tts_pregen_voice"
    private const val KEY_SID        = "kokoro_sid"
    private const val KEY_SELECTED_AT = "selected_at"
    // ⚠️ MỚI: lưu luôn ENGINE đang chọn (KOKORO hay ANDROID) — trước đây chỉ
    // lưu sid Kokoro, nên nếu lần cuối người dùng chọn "Android", thông tin
    // đó KHÔNG sống sót qua việc tắt app (TtsManager.activeEngineType chỉ
    // tồn tại trong RAM). Lưu bằng String (tên enum) để dễ đọc khi debug
    // trực tiếp file prefs, và để valueOf() tự validate, tránh phải tự map
    // số nguyên ↔ enum.
    private const val KEY_ENGINE_TYPE = "engine_type"

    private lateinit var prefs: SharedPreferences

    // ── MỚI: applicationContext lưu lại từ init() — CHỈ dùng để gọi
    // TtsPregenScheduler.enqueueWork(context) trong recordSelectedSid().
    // Dùng applicationContext (không phải Activity/Fragment context) nên an
    // toàn giữ lâu dài trong 1 object singleton, không rò rỉ Activity.
    // Nullable vì về lý thuyết recordSelectedSid() có thể bị gọi trước khi
    // init() từng chạy (vd thứ tự khởi tạo app thay đổi trong tương lai) —
    // nếu vậy đơn giản BỎ QUA bước enqueue ở lần gọi đó (không throw); lượt
    // enqueue ở điểm gọi (a) — MainActivity.onCreate() — vẫn sẽ tự chạy
    // Worker với đúng sid vừa lưu ngay sau đó, nên không mất dữ liệu, chỉ
    // trễ hơn 1 chút.
    private var appContext: Context? = null

    // Gọi 1 lần duy nhất, ở nơi khởi tạo app — cùng chỗ với
    // TtsReadingHistory.init()/TtsManager.init().
    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Nếu chưa từng lưu sid nào (lần đầu cài app) — mặc định sid=0,
        // khớp với giá trị mặc định của KokoroTtsEngine.currentSid khi chưa
        // ai gọi setSpeaker(). Không ghi ngay xuống prefs ở đây (chỉ trả về
        // mặc định khi đọc) — tránh tạo dữ liệu "giả" trước khi người dùng
        // thực sự tương tác với tính năng chọn giọng.
    }

    // ── Gọi mỗi khi người dùng đổi giọng Kokoro ──────────────────────────
    // Điểm gọi hợp lý nhất là ngay trong TtsManager.setKokoroSpeaker() —
    // để không phải sửa thêm bất kỳ đâu ở tầng UI (ReadingScreen chỉ đang
    // gọi TtsManager.setKokoroSpeaker(sid) sẵn có). Lời gọi đó đã được thêm
    // vào TtsManager ở bước tích hợp cuối cùng (Bước 1/2 dưới đây).
    fun recordSelectedSid(sid: Int, nowMillis: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putInt(KEY_SID, sid)
            .putLong(KEY_SELECTED_AT, nowMillis)
            .apply()

        // ── MỚI: tự enqueue TtsPregenWorker ngay khi giọng vừa đổi — an
        // toàn gọi nhiều lần nhờ ExistingWorkPolicy.KEEP ở
        // TtsPregenScheduler (xem TtsPregenScheduler.kt): nếu đã có 1 lượt
        // đang chạy, Worker TỰ phát hiện thay đổi này qua vòng lặp kiểm tra
        // trước mỗi item (checkNotInterrupted) rồi tự ngắt/tính lại, không
        // cần enqueue mới cũng đúng; nhưng nếu lượt trước ĐÃ CHẠY XONG (hết
        // việc, Worker đã dừng hẳn) thì bắt buộc phải enqueue lại ở đây,
        // nếu không Worker sẽ không bao giờ biết giọng vừa đổi cho tới lần
        // mở app kế tiếp — đây chính là lỗ hổng đã phát hiện và vá ở bước
        // tích hợp này (điểm gọi (c) trong thiết kế TtsPregenScheduler).
        appContext?.let { ctx -> TtsPregenScheduler.enqueueWork(ctx) }
    }

    // ── MỚI: Gọi mỗi khi người dùng chuyển engine (switchEngine() trong
    // TtsManager) — ghi nhớ lựa chọn để lần mở app kế tiếp tự khôi phục
    // đúng engine này (xem reconcileActiveEngine() ở TtsManager.kt), giống
    // hệt cách recordSelectedSid() đang lưu sid Kokoro. Không cần
    // enqueueWork() ở đây như recordSelectedSid() — đổi ENGINE (không phải
    // đổi SID) không ảnh hưởng gì tới việc pre-cache (TtsPregenWorker chỉ
    // quan tâm sid Kokoro mục tiêu, không quan tâm engine hiện tại đang
    // active là gì khi generate cache — xem TtsPregenWorker.kt).
    fun recordSelectedEngine(type: TtsManager.EngineType) {
        prefs.edit().putString(KEY_ENGINE_TYPE, type.name).apply()
    }

    // ── Engine đã chọn lần cuối — mặc định KOKORO nếu chưa từng lưu (lần
    // đầu cài app) hoặc nếu giá trị lưu bị hỏng/không hợp lệ (an toàn qua
    // try/catch thay vì để valueOf() ném exception làm crash app). ────────
    fun savedEngineType(): TtsManager.EngineType {
        val name = prefs.getString(KEY_ENGINE_TYPE, null) ?: return TtsManager.EngineType.KOKORO
        return try {
            TtsManager.EngineType.valueOf(name)
        } catch (e: IllegalArgumentException) {
            TtsManager.EngineType.KOKORO
        }
    }

    // ── sid đã lưu gần nhất (không quan tâm engine hiện tại là gì) ───────
    // Mặc định 0 nếu chưa từng lưu — khớp sid mặc định của KokoroTtsEngine.
    private fun savedSid(): Int = prefs.getInt(KEY_SID, 0)

    private fun savedAtMillis(): Long = prefs.getLong(KEY_SELECTED_AT, 0L)

    // ── Giọng MỤC TIÊU để pre-cache tại thời điểm gọi ────────────────────
    // Trả về null nếu KHÔNG có giọng nào cần pre-cache — xảy ra khi engine
    // đang active của TtsManager là ANDROID (không phải Kokoro). Đây là nơi
    // DUY NHẤT quyết định "có nên pre-cache lúc này không", TtsPregenWorker
    // chỉ cần gọi hàm này, không cần tự kiểm tra engine type ở nơi khác.
    fun currentTargetSid(): Int? {
        if (TtsManager.getCurrentEngineType() != TtsManager.EngineType.KOKORO) {
            return null
        }
        return savedSid()
    }

    // ── Kiểm tra: giọng đã đổi so với 1 mốc thời gian đã biết trước đó? ───
    // TtsPregenWorker gọi hàm này TRƯỚC MỖI ITEM để phát hiện việc đổi
    // giọng giữa chừng — truyền vào lastKnownSelectedAt (mốc thời gian của
    // lần đọc snapshot trước đó trong cùng vòng lặp), nếu savedAtMillis()
    // hiện tại lớn hơn → nghĩa là người dùng vừa chọn giọng mới, Worker cần
    // ngắt và bắt đầu lại theo giọng mới.
    fun hasChangedSince(lastKnownSelectedAt: Long): Boolean {
        return savedAtMillis() > lastKnownSelectedAt
    }

    // ── Mốc thời gian của lần chọn giọng gần nhất ────────────────────────
    // Dùng làm giá trị "lastKnownSelectedAt" ban đầu khi Worker mới bắt đầu
    // 1 vòng xử lý, để các lần kiểm tra hasChangedSince() tiếp theo so sánh
    // đúng chuẩn.
    fun currentSelectedAtMillis(): Long = savedAtMillis()
}