// KokoroTtsEngine.kt
// Đặt tại: com/eleap/eleap/core/tts/KokoroTtsEngine.kt
//
// Bọc sherpa-onnx (OfflineTts, model Kokoro int8 — bản kokoro-int8-en-v0_19,
// CHỈ tiếng Anh, 11 giọng) — implement TtsEngine để TtsManager dùng chung
// interface với AndroidTtsEngine.
//
// ⚠️ TÊN CLASS/PACKAGE CỦA SHERPA-ONNX: import bên dưới dựa theo API phổ
// biến nhất của sherpa-onnx-kotlin (package com.k2fsa.sherpa.onnx). Nếu bản
// .aar bạn dùng đặt tên khác, dùng autocomplete của Android Studio để sửa
// lại đúng tên class/field/method — cấu trúc tổng thể vẫn giữ nguyên.
//
// Model KHÔNG đọc trực tiếp từ assets/ (xem AssetCopier.kt) — phải copy ra
// filesDir trước vì code C++ bên dưới cần đường dẫn file thật trên đĩa.
//
// KHÔNG phải singleton — TtsManager tự giữ 1 instance của class này.
//
// ⚠️ ĐÃ XÁC NHẬN: generate() treo vĩnh viễn trên emulator dùng lớp dịch nhị
// phân ARM→x86 (ndk_translation) — đây là giới hạn môi trường emulator,
// KHÔNG phải bug logic. Chạy bình thường trên thiết bị Android thật (arm64).
//
// ⚠️ ĐÃ ĐỔI MODEL (lần 2): quay lại kokoro-int8-multi-lang-v1_0 (Anh+Trung
// +các ngôn ngữ khác, 53 giọng, int8, model.int8.onnx) — thay cho bản
// kokoro-int8-en-v0_19 (chỉ tiếng Anh, 11 giọng) đã dùng trước đó, vì bản
// en-v0_19 chỉ có 11 giọng, không đủ lựa chọn. Do đó:
//   - Tên file model VẪN là "model.int8.onnx" (tên trùng nhưng nội dung
//     khác hẳn — nhớ xoá cache cũ ở filesDir/kokoro khi đổi model, xem
//     AssetCopier.kt, nếu không app sẽ dùng nhầm file .copied cũ).
//   - THÊM LẠI tham số "lexicon" trong OfflineTtsKokoroModelConfig — bản
//     multi-lang BẮT BUỘC cần 3 file lexicon-us-en.txt/lexicon-gb-en.txt/
//     lexicon-zh.txt đi kèm, thiếu là generate() lỗi hoặc phát âm sai.
//   - numSpeakers giờ là 53 (sid 0-52), trong đó CHỈ sid 0-27 là tiếng Anh:
//       0-10  = af_alloy, af_aoede, af_bella, af_heart, af_jessica, af_kore,
//               af_nicole, af_nova, af_river, af_sarah, af_sky   (nữ, Mỹ)
//       11-19 = am_adam, am_echo, am_eric, am_fenrir, am_liam, am_michael,
//               am_onyx, am_puck, am_santa                        (nam, Mỹ)
//       20-23 = bf_alice, bf_emma, bf_isabella, bf_lily          (nữ, Anh-Anh)
//       24-27 = bm_daniel, bm_fable, bm_george, bm_lewis         (nam, Anh-Anh)
//     sid 28 trở đi là ngôn ngữ khác (Tây Ban Nha/Pháp/Hindi/Ý/Nhật/Bồ/Trung)
//     — KHÔNG dùng cho app này (chỉ đọc tiếng Anh), UI (ReadingScreen.kt)
//     chỉ nên cho chọn trong khoảng 0-27.
//
// ⚠️ currentSid (speaker id) — có thể đổi qua setSpeaker(sid), dùng tạm
// thời để thử nghiệm/so sánh các giọng khác nhau trong model. Mặc định vẫn
// là 0 để không đổi hành vi cũ nếu không ai gọi setSpeaker().
//
// ⚠️ MỚI (core/tts/pregen/): generateAudio(text, sid) — sinh audio thô cho
// TtsPregenWorker lưu cache, KHÔNG phát ra loa. Dùng CHUNG generateMutex với
// speak() — bắt buộc, vì OfflineTts không thread-safe (đã ghi chú ở
// generateMutex bên dưới): nếu để 2 lời gọi generate() (1 từ speak() lúc
// người dùng đang nghe, 1 từ generateAudio() lúc Worker đang pre-cache ngầm)
// chạy đồng thời trên cùng 1 instance OfflineTts, dễ treo hoặc crash native
// vĩnh viễn. Nhờ dùng chung mutex, 2 luồng (phát trực tiếp + pre-cache ngầm)
// tự động xếp hàng chờ nhau, không cần thêm cơ chế đồng bộ nào khác.
package com.eleap.eleap.core.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class KokoroTtsEngine : TtsEngine {

    private val TAG = "KokoroTtsEngine"

    private var tts: OfflineTts? = null
    private var isReadyFlag = false

    // speed truyền cho generate(): 1.0 = bình thường, giống ngữ nghĩa
    // currentRate mà TtsManager đang dùng cho AndroidTtsEngine — không cần
    // quy đổi gì thêm khi TtsManager gọi setSpeechRate() xuống đây.
    private var currentSpeed: Float = 1.0f

    // ── Giọng đang chọn (speaker id trong model multi-speaker) ─────────────
    // Model kokoro-int8-en-v0_19 có 11 giọng, sid hợp lệ: 0-10.
    @Volatile
    private var currentSid: Int = 0

    // Scope riêng cho việc load model (nặng, nên chạy nền) + generate audio
    // (cũng nặng, CPU-bound) — Dispatchers.Default phù hợp cho cả 2 việc.
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var currentJob: Job? = null
    private var audioTrack: AudioTrack? = null

    // ── Chặn 2 lời gọi generate() chạy đồng thời trên cùng 1 OfflineTts ──────
    // instance. QUAN TRỌNG: coroutine cancel() KHÔNG thể ngắt 1 lời gọi JNI
    // đang blocking bên trong engine.generate() (đây là native code, không
    // phải suspend fun thật sự có checkpoint để cancel). Nếu người dùng bấm
    // từ tiếp theo trong lúc generate() của từ trước còn đang chạy dở, PHẢI
    // đợi generate() cũ chạy xong (dù kết quả sẽ bị bỏ qua ngay sau đó) rồi
    // mới bắt đầu generate() mới — tuyệt đối không gọi song song, dễ treo
    // hoặc crash native vĩnh viễn vì OfflineTts không đảm bảo thread-safe.
    //
    // ⚠️ Cũng CHÍNH mutex này bảo vệ generateAudio() (pre-cache, dùng bởi
    // TtsPregenWorker) — xem ghi chú ở đầu file.
    private val generateMutex = Mutex()

    // ── MỚI: kiểm tra biên độ audio vừa generate xong — phát hiện trường
    // hợp generate() "chạy xong bình thường, không exception, số samples
    // đúng" NHƯNG nội dung lại gần như toàn số 0 (im lặng thật sự, không
    // phải lỗi hiển thị/playback). Đây là cách DUY NHẤT phân biệt chắc chắn
    // 2 khả năng: (a) generate() ra audio câm thật (bug ở model/input text),
    // hay (b) audio bình thường nhưng lỗi ở tầng phát lại (MediaPlayer/
    // AudioTrack/cache). Ngưỡng 0.01f là biên độ tối đa (thang [-1,1]) — âm
    // thanh giọng nói bình thường luôn có đỉnh cao hơn nhiều so với mức
    // này, kể cả đoạn nói khẽ nhất; dưới ngưỡng này gần như chắc chắn là im
    // lặng/câm chứ không phải giọng nhỏ.
    private fun logAmplitudeCheck(tag: String, text: String, samples: FloatArray, contextSuffix: String = "") {
        val maxAmplitude = samples.maxOfOrNull { kotlin.math.abs(it) } ?: 0f
        Log.d(TAG, "$tag: biên độ tối đa=$maxAmplitude cho \"$text\"$contextSuffix")
        if (maxAmplitude < 0.01f) {
            Log.w(
                TAG,
                "$tag: audio CÂM THẬT SỰ (biên độ tối đa=$maxAmplitude, gần như toàn số 0) " +
                        "cho \"$text\"$contextSuffix — đây là lỗi ở generate()/model, KHÔNG PHẢI lỗi playback. " +
                        "Nếu thấy log này, vấn đề nằm ở input text hoặc model, không phải ở " +
                        "TtsPlaybackRouter/TtsAudioCache/MediaPlayer."
            )
        }
    }

    // Đếm số lần gọi speak() — dùng để lời gọi generate() cũ (đang đợi tới
    // lượt hoặc vừa generate xong sau khi bị "vượt mặt") tự biết mình đã lỗi
    // thời, không phát audio nữa dù generate() đã hoàn tất.
    private var latestRequestId = 0L

    // ── Tự động tính numThreads theo số core thiết bị, thay vì hardcode cố
    // định — công thức bậc thang, KHÔNG dùng hết toàn bộ core:
    //   - ≤4 core:  chừa lại 1 core (tối thiểu vẫn dùng 1) — máy yếu, nếu
    //               dùng HẾT toàn bộ core thì lúc TtsPregenWorker đang
    //               generate() ngầm (13-18s/lần, chiếm dụng mọi core suốt
    //               thời gian đó), luồng phát audio (MediaPlayer decode/
    //               render khi phát file cache, hoặc AudioTrack.write() khi
    //               generate on-the-fly) bị đói CPU → giật, mất đoạn nội
    //               dung, phải nghe lại nhiều lần mới đủ câu. Chừa 1 core
    //               đảm bảo luôn có tài nguyên tối thiểu cho audio/UI thread
    //               chạy song song, dù generate() vẫn đang chạy dở.
    //   - 5-8 core: giới hạn 4 (tránh dính core LITTLE yếu trong kiến trúc
    //               big.LITTLE khi ONNX Runtime chia việc đều ra mọi core —
    //               core chậm nhất sẽ kéo chậm cả batch)
    //   - >8 core:  giới hạn 6 (máy cao cấp, vẫn chừa core cho UI thread +
    //               hệ thống chạy song song lúc generate(), lợi ích thêm
    //               thread cũng đã bão hoà sau ngưỡng này)
    // Tính 1 lần, cache lại — availableProcessors() không đổi trong 1 phiên
    // chạy app.
    private fun calculateOptimalThreads(): Int {
        val coreCount = Runtime.getRuntime().availableProcessors()
        val numThreads = when {
            coreCount <= 4 -> (coreCount - 1).coerceAtLeast(1)
            coreCount <= 8 -> 4
            else           -> 6
        }
        Log.d(TAG, "calculateOptimalThreads: thiết bị có $coreCount core → dùng $numThreads thread")
        return numThreads
    }

    override fun init(context: Context, onReady: (success: Boolean) -> Unit) {
        if (tts != null) {
            Log.d(TAG, "init: đã init từ trước, bỏ qua")
            onReady(isReadyFlag)
            return
        }

        engineScope.launch {
            try {
                // Copy asset ra filesDir (chỉ copy thật sự ở lần đầu tiên,
                // các lần sau tự bỏ qua nhờ marker file — xem AssetCopier.kt).
                val modelDir = AssetCopier.copyAssetDirIfNeeded(context, "kokoro")

                // ⚠️ ĐÃ ĐỔI: model.int8.onnx (kokoro-int8-multi-lang-v1_0) —
                // BẮT BUỘC truyền "lexicon" trỏ tới 3 file lexicon-us-en.txt/
                // lexicon-gb-en.txt/lexicon-zh.txt, khác với bản en-v0_19
                // trước đó (không cần lexicon). Thiếu tham số này, generate()
                // vẫn có thể chạy nhưng phát âm sai/lỗi với nhiều từ.
                val kokoroConfig = OfflineTtsKokoroModelConfig(
                    model   = "$modelDir/model.int8.onnx",
                    voices  = "$modelDir/voices.bin",
                    tokens  = "$modelDir/tokens.txt",
                    dataDir = "$modelDir/espeak-ng-data",
                    lexicon = "$modelDir/lexicon-us-en.txt,$modelDir/lexicon-gb-en.txt,$modelDir/lexicon-zh.txt",
                )

                val modelConfig = OfflineTtsModelConfig(
                    kokoro     = kokoroConfig,
                    numThreads = calculateOptimalThreads(),
                    debug      = false,
                    provider   = "cpu",
                )

                val config = OfflineTtsConfig(model = modelConfig)

                // assetManager = null vì đọc từ đường dẫn file thật (filesDir),
                // không đọc thẳng từ APK assets.
                tts = OfflineTts(assetManager = null, config = config)
                isReadyFlag = true

                Log.d(TAG, "init: Kokoro sẵn sàng, sampleRate=${tts?.sampleRate()}, numSpeakers=${tts?.numSpeakers()}")
                onReady(true)
            } catch (e: Exception) {
                Log.e(TAG, "init: lỗi khởi tạo Kokoro", e)
                isReadyFlag = false
                onReady(false)
            }
        }
    }

    override fun speak(text: String) {
        if (text.isBlank() || !isReadyFlag) {
            if (!isReadyFlag) Log.d(TAG, "speak: engine chưa sẵn sàng, bỏ qua \"$text\"")
            return
        }

        val myRequestId = ++latestRequestId
        stopPlayback()

        currentJob = engineScope.launch {
            generateMutex.withLock {
                if (myRequestId != latestRequestId) {
                    Log.d(TAG, "speak: \"$text\" đã lỗi thời (có request mới hơn), bỏ qua")
                    return@withLock
                }

                try {
                    val engine = tts ?: return@withLock
                    // Chụp lại sid TẠI THỜI ĐIỂM bắt đầu generate — nếu người
                    // dùng đổi giọng ngay trong lúc generate() đang chạy dở,
                    // câu đang đọc dở vẫn phát xong bằng giọng đã chọn lúc
                    // bắt đầu, không bị đổi giọng giữa chừng.
                    val sidToUse = currentSid
                    Log.d(TAG, "speak: bắt đầu generate() cho \"$text\" (requestId=$myRequestId, sid=$sidToUse)")
                    val startTime = System.currentTimeMillis()
                    val audio = engine.generate(text = text, sid = sidToUse, speed = currentSpeed)
                    val elapsed = System.currentTimeMillis() - startTime

                    Log.d(
                        TAG,
                        "speak: generate() xong trong ${elapsed}ms (requestId=$myRequestId, sid=$sidToUse), " +
                                "samples=${audio.samples.size}, sampleRate=${audio.sampleRate}"
                    )
                    logAmplitudeCheck("speak", text, audio.samples)

                    if (myRequestId != latestRequestId) {
                        Log.d(TAG, "speak: \"$text\" generate xong nhưng đã lỗi thời, bỏ qua phát")
                        return@withLock
                    }

                    if (audio.samples.isEmpty()) {
                        Log.w(TAG, "speak: generate() trả về 0 sample — không có gì để phát")
                        return@withLock
                    }
                    playAudio(audio.samples, audio.sampleRate)
                } catch (e: Exception) {
                    Log.e(TAG, "speak: lỗi khi sinh/phát audio cho \"$text\"", e)
                }
            }
        }
    }

    // ── MỚI (core/tts/pregen/): sinh audio thô, KHÔNG phát ra loa ───────────
    // Dùng bởi TtsPregenWorker để lưu cache — xem TtsAudioCache.kt. Khác
    // speak() ở 3 điểm:
    //   1. suspend fun thật sự trả về kết quả (speak() fire-and-forget, tự
    //      launch coroutine riêng rồi trả về ngay, không có gì để await).
    //   2. Nhận sid làm THAM SỐ tường minh, KHÔNG dùng currentSid (biến này
    //      chỉ có ý nghĩa cho phát trực tiếp qua speak()/setSpeaker() — pre-
    //      cache luôn generate theo đúng sid mà TtsPregenWorker truyền vào,
    //      độc lập với giọng đang active cho phát trực tiếp).
    //   3. KHÔNG gọi playAudio()/stopPlayback() — không đụng gì tới
    //      AudioTrack đang có thể đang phát dở cho người dùng.
    //   4. Luôn generate ở speed=1.0 (tốc độ chuẩn) — pre-cache KHÔNG theo
    //      currentSpeed (tốc độ đọc người dùng đang chọn), vì tốc độ có thể
    //      đổi bất kỳ lúc nào và ta không muốn nhân bản cache theo từng mức
    //      tốc độ. TtsPlaybackRouter (bước 8) sẽ tự quyết định cách áp dụng
    //      tốc độ khi phát lại file cache (nếu cần, ở phạm vi ngoài bước này).
    //
    // Vẫn qua generateMutex — xếp hàng chung với speak(), tuyệt đối không gọi
    // generate() JNI song song trên 2 luồng khác nhau (xem ghi chú ở
    // generateMutex phía trên).
    override suspend fun generateAudio(text: String, sid: Int, readingId: String): TtsAudioResult? {
        if (text.isBlank() || !isReadyFlag) {
            Log.d(TAG, "generateAudio: engine chưa sẵn sàng hoặc text rỗng, bỏ qua \"$text\" (reading=$readingId)")
            return null
        }

        return withContext(Dispatchers.Default) {
            generateMutex.withLock {
                try {
                    val engine = tts ?: return@withLock null
                    Log.d(TAG, "generateAudio: bắt đầu generate() cho \"$text\" (reading=$readingId, sid=$sid, pre-cache)")
                    val startTime = System.currentTimeMillis()
                    val audio = engine.generate(text = text, sid = sid, speed = 1.0f)
                    val elapsed = System.currentTimeMillis() - startTime

                    Log.d(
                        TAG,
                        "generateAudio: generate() xong trong ${elapsed}ms (reading=$readingId, sid=$sid), " +
                                "samples=${audio.samples.size}, sampleRate=${audio.sampleRate}"
                    )
                    logAmplitudeCheck("generateAudio", text, audio.samples, contextSuffix = " (reading=$readingId, sid=$sid)")

                    if (audio.samples.isEmpty()) {
                        Log.w(TAG, "generateAudio: generate() trả về 0 sample cho \"$text\" (reading=$readingId, sid=$sid)")
                        null
                    } else {
                        TtsAudioResult(samples = audio.samples, sampleRate = audio.sampleRate)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "generateAudio: lỗi khi sinh audio cho \"$text\" (reading=$readingId, sid=$sid)", e)
                    null
                }
            }
        }
    }

    // ── Đổi giọng đọc theo speaker id ────────────────────────────────────
    // Model kokoro-int8-multi-lang-v1_0 có 53 giọng: sid hợp lệ 0-52.
    // Chỉ sid 0-27 là tiếng Anh (Mỹ + Anh-Anh) — xem bảng chi tiết ở comment
    // đầu file. sid 28-52 là ngôn ngữ khác, không nên dùng cho app này.
    override fun setSpeaker(sid: Int) {
        currentSid = sid
        Log.d(TAG, "setSpeaker: đổi sang sid=$sid")
    }

    private fun playAudio(samples: FloatArray, sampleRate: Int) {
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        val bufferSizeBytes = maxOf(minBufferSize, samples.size * 4)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSizeBytes)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        audioTrack = track
        track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
        track.play()
    }

    private fun stopPlayback() {
        audioTrack?.let {
            try {
                it.stop()
                it.release()
            } catch (e: Exception) {
                // Track đã stop/release từ trước — bỏ qua.
            }
        }
        audioTrack = null
    }

    override fun stop() {
        currentJob?.cancel()
        stopPlayback()
    }

    override fun setSpeechRate(rate: Float) {
        currentSpeed = rate
    }

    override fun isReady(): Boolean = isReadyFlag

    override fun shutdown() {
        currentJob?.cancel()
        stopPlayback()
        try {
            tts?.release()
        } catch (e: Exception) {
            Log.e(TAG, "shutdown: lỗi khi release Kokoro", e)
        }
        tts = null
        isReadyFlag = false
        Log.d(TAG, "shutdown: đã giải phóng Kokoro")
    }
}