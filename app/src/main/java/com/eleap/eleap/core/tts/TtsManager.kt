// TtsManager.kt
// Đặt tại: com/eleap/eleap/core/tts/TtsManager.kt
//
// Singleton thủ công, KHÔNG dùng Hilt/DI — cùng phong cách với CurrentUser,
// SyncCursor, SupabaseClientProvider.
//
// TtsManager trừu tượng hoá qua interface TtsEngine (xem TtsEngine.kt) — có
// 2 cài đặt: AndroidTtsEngine (bọc TextToSpeech cũ) và KokoroTtsEngine (bọc
// sherpa-onnx). API công khai cũ (init/speak/stop/setSpeechRate/
// getSpeechRate/shutdown) giữ NGUYÊN.
//
// ⚠️ MỚI (tạm thời, để debug/thử nghiệm chọn giọng): giờ TtsManager khởi
// tạo và giữ CẢ 2 engine cùng lúc (thay vì chỉ giữ 1 "activeEngine" chọn
// một lần lúc init rồi thôi) — để có thể chuyển qua lại giữa Android TTS và
// Kokoro bất kỳ lúc nào qua switchEngine(), và đổi giọng Kokoro qua
// setKokoroSpeaker(), phục vụ nút bấm thử nghiệm tạm thời ở ReadingScreen.
//
// ⚠️ MỚI (core/tts/pregen/): thêm 2 hàm phục vụ TtsPregenWorker —
// ensureKokoroReady()/generateKokoroAudioForCache() — KHÔNG động tới bất kỳ
// logic cũ nào ở trên. Lý do cần 2 hàm riêng thay vì tái dùng init()/speak()
// sẵn có:
//   - Worker có thể chạy TRƯỚC KHI app (MainActivity) từng gọi
//     TtsManager.init() (vd process bị hệ thống restart chỉ để chạy
//     WorkManager job), nên cần 1 hàm suspend CHỜ ĐƯỢC tới khi Kokoro sẵn
//     sàng, thay vì init() kiểu callback fire-and-forget như cũ.
//   - speak() luôn phát ra loa ngay và không trả lại được audio thô; Worker
//     cần lấy samples để tự ghi file cache (qua TtsAudioCache), không phải
//     phát ra loa — nên cần đường generateAudio() riêng, không dùng speak().
package com.eleap.eleap.core.tts

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.eleap.eleap.core.tts.pregen.TtsVoiceSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object TtsManager {

    private const val TAG = "TtsManager"

    private const val PREFS_NAME    = "tts_settings"
    private const val KEY_RATE      = "speech_rate"
    private const val DEFAULT_RATE  = 1.0f
    const val MIN_RATE = 0.1f
    const val MAX_RATE = 2.0f

    enum class EngineType { ANDROID, KOKORO }

    private lateinit var prefs: SharedPreferences

    private var androidEngine: AndroidTtsEngine? = null
    private var kokoroEngine: KokoroTtsEngine? = null

    private var activeEngine: TtsEngine? = null
    private var activeEngineType: EngineType = EngineType.KOKORO

    // ⚠️ MỚI: bản StateFlow của activeEngineType — để UI (vd ReadingScreen)
    // có thể QUAN SÁT ĐỘNG lúc engine active thật sự đổi, thay vì chỉ đọc 1
    // lần lúc mở màn qua getCurrentEngineType(). Cần thiết vì
    // reconcileActiveEngine() có thể tự chuyển engine NHIỀU LẦN không đồng
    // bộ với UI — vd Android TTS ready sớm (~1s) trong khi Kokoro còn đang
    // load model (~5-13s), TtsManager tạm active Android làm fallback, rồi
    // vài giây sau tự chuyển LẠI đúng Kokoro khi nó ready. Nếu UI chỉ đọc
    // getCurrentEngineType() 1 LẦN đúng lúc đang tạm fallback Android, label
    // sẽ hiển thị sai và KHÔNG BAO GIỜ tự cập nhật lại cho tới khi UI đó bị
    // tạo lại (vd rời màn đọc rồi vào lại) — đây chính là lỗi đã phát hiện.
    // Backing field activeEngineType (var thường) vẫn giữ lại để nội bộ
    // TtsManager đọc/ghi đồng bộ, không đổi cách dùng ở các hàm cũ.
    private val _activeEngineTypeFlow = MutableStateFlow(activeEngineType)
    val activeEngineTypeFlow: StateFlow<EngineType> = _activeEngineTypeFlow

    private var isInitializing = false

    // Tốc độ đọc hiện tại — đọc từ prefs khi init(), áp lại mỗi lần app mở
    // lại, và áp lại cho engine MỚI mỗi khi switchEngine().
    private var currentRate: Float = DEFAULT_RATE

    // Hàng đợi 1 phần tử: nếu speak() được gọi TRƯỚC khi engine nào đó init
    // xong.
    private var pendingText: String? = null

    // Gọi 1 lần duy nhất, ở MainActivity.onCreate().
    fun init(context: Context) {
        if (activeEngine != null || isInitializing) {
            Log.d(TAG, "init: đã init hoặc đang init, bỏ qua")
            return
        }
        isInitializing = true

        prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        currentRate = prefs.getFloat(KEY_RATE, DEFAULT_RATE)

        // ⚠️ MỚI: init TtsVoiceSnapshot NGAY TỪ ĐÂY (idempotent, an toàn gọi
        // lại) — reconcileActiveEngine() bên dưới cần đọc
        // TtsVoiceSnapshot.savedEngineType() ngay khi 2 engine bắt đầu báo
        // ready, nên phải đảm bảo prefs của nó đã sẵn sàng TRƯỚC khi bất kỳ
        // callback init() nào của kokoro/android có thể fire (dù chúng chạy
        // async, callback vẫn có thể fire rất sớm nếu engine ready gần như
        // ngay lập tức, vd Android TextToSpeech).
        TtsVoiceSnapshot.init(context)

        val kokoro = KokoroTtsEngine()
        kokoroEngine = kokoro
        kokoro.init(context) { kokoroReady ->
            if (kokoroReady) {
                Log.d(TAG, "init: Kokoro sẵn sàng")
            } else {
                Log.w(TAG, "init: Kokoro init thất bại")
            }
            reconcileActiveEngine()
        }

        // Luôn init cả Android TTS song song — dù Kokoro có sẵn sàng hay
        // không, để có thể chuyển qua lại bất kỳ lúc nào qua switchEngine().
        val android = AndroidTtsEngine()
        androidEngine = android
        android.init(context) { androidReady ->
            if (androidReady) {
                Log.d(TAG, "init: Android TTS sẵn sàng")
            } else {
                Log.e(TAG, "init: Android TTS init thất bại")
            }
            reconcileActiveEngine()
            isInitializing = false
        }
    }

    // ── MỚI: chọn engine ACTIVE dựa trên lựa chọn đã lưu lần cuối
    // (TtsVoiceSnapshot.savedEngineType()) — thay cho logic cũ "engine nào
    // init xong TRƯỚC (activeEngine == null) thì active engine đó", vốn phụ
    // thuộc vào THỨ TỰ 2 engine sẵn sàng lúc runtime (không đảm bảo), nên
    // KHÔNG khôi phục đúng lựa chọn của người dùng sau khi tắt hẳn app —
    // đúng lỗ hổng cần vá theo yêu cầu "giống cài đặt cỡ chữ": phải khôi
    // phục được NGAY CẢ SAU KHI FORCE-CLOSE, không phụ thuộc timing.
    //
    // Gọi lại hàm này ở CẢ HAI callback init() (kokoro lẫn android) mỗi khi
    // 1 trong 2 chuyển trạng thái ready/thất bại — vì tại thời điểm gọi
    // init(), chưa biết engine nào sẽ ready trước, nên phải re-evaluate mỗi
    // khi có thêm thông tin mới.
    //
    // Ưu tiên: nếu engine TRÙNG savedType đã ready → active nó NGAY, kể cả
    // đang active tạm 1 engine KHÁC trước đó (chủ động CHUYỂN LẠI cho đúng
    // lựa chọn đã lưu — vd Android ready trước, active tạm, rồi Kokoro ready
    // sau nhưng savedType=KOKORO → tự chuyển sang Kokoro ngay). Nếu engine
    // trùng savedType CHƯA ready → tạm active engine còn lại (nếu nó đã
    // ready) làm fallback, để không im lặng hoàn toàn trong lúc chờ; khi
    // engine đúng ready, lần gọi kế tiếp sẽ tự chuyển lại đúng lựa chọn.
    private fun reconcileActiveEngine() {
        val savedType = TtsVoiceSnapshot.savedEngineType()
        val kokoro = kokoroEngine
        val android = androidEngine
        val kokoroReady = kokoro != null && kokoro.isReady()
        val androidReady = android != null && android.isReady()

        val target: TtsEngine
        val targetType: EngineType
        when (savedType) {
            EngineType.KOKORO -> when {
                kokoroReady -> { target = kokoro!!; targetType = EngineType.KOKORO }
                androidReady -> { target = android!!; targetType = EngineType.ANDROID }
                else -> return // chưa có gì sẵn sàng, chờ callback kế tiếp
            }
            EngineType.ANDROID -> when {
                androidReady -> { target = android!!; targetType = EngineType.ANDROID }
                kokoroReady -> { target = kokoro!!; targetType = EngineType.KOKORO }
                else -> return
            }
        }

        if (activeEngine !== target) {
            activeEngine?.stop()
            activateEngine(target, targetType)
            Log.d(TAG, "reconcileActiveEngine: active=$targetType (đã lưu=$savedType)")
        }
    }

    private fun activateEngine(engine: TtsEngine, type: EngineType) {
        engine.setSpeechRate(currentRate)
        activeEngine = engine
        activeEngineType = type
        // ⚠️ MỚI: đồng bộ StateFlow ngay khi engine active thật sự đổi — đây
        // là điểm DUY NHẤT activeEngineType được gán, nên chỉ cần cập nhật ở
        // đây là đủ cho MỌI nơi gọi (reconcileActiveEngine() lẫn switchEngine()).
        _activeEngineTypeFlow.value = type

        pendingText?.let { text ->
            pendingText = null
            speak(text)
        }
    }

    // ── MỚI: chuyển đổi engine đang active — trả về true nếu chuyển thành
    // công (engine đích đã sẵn sàng), false nếu chưa sẵn sàng (giữ nguyên
    // engine cũ). ────────────────────────────────────────────────────────
    fun switchEngine(type: EngineType): Boolean {
        val target: TtsEngine? = when (type) {
            EngineType.ANDROID -> androidEngine
            EngineType.KOKORO  -> kokoroEngine
        }
        if (target == null || !target.isReady()) {
            Log.w(TAG, "switchEngine: $type chưa sẵn sàng, không chuyển")
            return false
        }
        activeEngine?.stop()
        activateEngine(target, type)
        TtsVoiceSnapshot.recordSelectedEngine(type)
        Log.d(TAG, "switchEngine: đã chuyển sang $type")
        return true
    }

    fun getCurrentEngineType(): EngineType = activeEngineType

    // ── MỚI: đổi giọng Kokoro (speaker id) — không có tác dụng gì nếu
    // Kokoro chưa init hoặc đang không active, nhưng vẫn set để lần switch
    // sang Kokoro kế tiếp dùng đúng giọng đã chọn. ─────────────────────────
    //
    // ⚠️ MỚI (core/tts/pregen/): thêm 1 dòng ghi lại sid vừa chọn xuống
    // TtsVoiceSnapshot — ĐÂY LÀ ĐIỂM GỌI DUY NHẤT, đúng như đã chốt trong
    // comment sẵn có ở TtsVoiceSnapshot.recordSelectedSid(): đặt ngay trong
    // setKokoroSpeaker() để không phải sửa thêm bất kỳ đâu ở tầng UI
    // (ReadingScreen chỉ đang gọi TtsManager.setKokoroSpeaker(sid) sẵn có,
    // không cần biết gì thêm về pre-cache). Gọi SAU khi set engine (không
    // ảnh hưởng thứ tự, chỉ để mạch code đọc tự nhiên: "đổi giọng active
    // trước, ghi nhớ lại sau"). TtsVoiceSnapshot tự lo việc enqueue
    // TtsPregenWorker bên trong nó — TtsManager không cần biết gì về
    // WorkManager/TtsPregenScheduler.
    fun setKokoroSpeaker(sid: Int) {
        kokoroEngine?.setSpeaker(sid)
        TtsVoiceSnapshot.recordSelectedSid(sid)
    }

    // Nói 1 câu — luôn NGẮT câu đang đọc dở, uỷ quyền thẳng xuống engine
    // đang active.
    fun speak(text: String) {
        if (text.isBlank()) return

        val engine = activeEngine
        if (engine == null || !engine.isReady()) {
            pendingText = text
            Log.d(TAG, "speak: chưa có engine sẵn sàng, lưu tạm: \"$text\"")
            return
        }
        engine.speak(text)
    }

    fun stop() {
        activeEngine?.stop()
    }

    fun setSpeechRate(rate: Float) {
        val clamped = rate.coerceIn(MIN_RATE, MAX_RATE)
        currentRate = clamped
        activeEngine?.setSpeechRate(clamped)
        if (::prefs.isInitialized) {
            prefs.edit().putFloat(KEY_RATE, clamped).apply()
        }
        Log.d(TAG, "setSpeechRate: $clamped")
    }

    fun getSpeechRate(): Float = currentRate

    // ─────────────────────────────────────────────────────────────────────
    // ── MỚI (core/tts/pregen/): phục vụ TtsPregenWorker ─────────────────
    // ─────────────────────────────────────────────────────────────────────

    // ── Đảm bảo Kokoro đã sẵn sàng, CHỜ ĐƯỢC (suspend) — khác hẳn init()
    // ở trên (fire-and-forget, dùng callback). Worker có thể chạy trong 1
    // process mới toanh do WorkManager tự khởi động lại (vd hệ thống kill
    // app rồi restart process chỉ để chạy job nền), lúc đó TtsManager CHƯA
    // từng được MainActivity gọi init() — nên hàm này TỰ gọi init(context)
    // (idempotent, an toàn nếu gọi lại khi đã init rồi) rồi poll
    // kokoroEngine?.isReady() mỗi ~200ms cho tới khi sẵn sàng hoặc hết
    // timeout. Trả về false nếu hết timeout mà Kokoro vẫn chưa sẵn sàng
    // (vd máy yếu, model load quá lâu, hoặc init thất bại hẳn) — Worker sẽ
    // tự coi như "không có gì để làm" ở lượt chạy này, KHÔNG phải lỗi.
    //
    // Không quan tâm activeEngine đang là gì (có thể đang active Android
    // TTS) — chỉ cần kokoroEngine tồn tại và isReady(), vì mục đích ở đây
    // là GENERATE AUDIO THÔ để cache, không phải đổi giọng đang phát cho
    // người dùng.
    suspend fun ensureKokoroReady(context: Context, timeoutMs: Long = 20_000L): Boolean {
        // Idempotent — nếu đã init hoặc đang init dở thì init() tự bỏ qua,
        // không tạo thêm engine mới.
        init(context)

        val pollIntervalMs = 200L
        var waited = 0L
        while (waited < timeoutMs) {
            val kokoro = kokoroEngine
            if (kokoro != null && kokoro.isReady()) {
                return true
            }
            delay(pollIntervalMs)
            waited += pollIntervalMs
        }

        val readyNow = kokoroEngine?.isReady() == true
        if (!readyNow) {
            Log.w(TAG, "ensureKokoroReady: hết timeout (${timeoutMs}ms) mà Kokoro vẫn chưa sẵn sàng")
        }
        return readyNow
    }

    // ── Sinh audio thô cho 1 (text, sid) — KHÔNG phát ra loa, dùng cho
    // TtsPregenWorker lưu file cache. Uỷ quyền thẳng xuống
    // kokoroEngine.generateAudio() (KokoroTtsEngine tự lo mutex dùng chung
    // với speak(), tự set speed=1.0f cố định cho mục đích cache — xem
    // KokoroTtsEngine.kt). Trả về null nếu kokoroEngine chưa tồn tại (chưa
    // init) — Worker nên gọi ensureKokoroReady() TRƯỚC khi gọi hàm này để
    // tránh rơi vào trường hợp null này một cách không cần thiết.
    //
    // ⚠️ MỚI: thêm readingId (CHỈ để log — xem KokoroTtsEngine.generateAudio())
    // — TtsPregenWorker.processItem() đã có sẵn readingId trong tay nên chỉ
    // cần truyền xuyên qua, không cần tự tra cứu gì thêm ở đây.
    suspend fun generateKokoroAudioForCache(text: String, sid: Int, readingId: String): TtsAudioResult? {
        val kokoro = kokoroEngine
        if (kokoro == null) {
            Log.w(TAG, "generateKokoroAudioForCache: kokoroEngine chưa init, trả về null (reading=$readingId)")
            return null
        }
        return kokoro.generateAudio(text, sid, readingId)
    }

    // Gọi ở MainActivity.onDestroy() — giải phóng cả 2 engine, tránh rò rỉ.
    fun shutdown() {
        androidEngine?.shutdown()
        kokoroEngine?.shutdown()
        androidEngine = null
        kokoroEngine = null
        activeEngine = null
        isInitializing = false
        pendingText = null
        Log.d(TAG, "shutdown: đã giải phóng engine")
    }
}