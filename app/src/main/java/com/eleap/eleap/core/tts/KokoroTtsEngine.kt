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
// ⚠️ ĐÃ ĐỔI MODEL: chuyển từ kokoro-multi-lang-v1_0 (Anh+Trung, 53 giọng,
// fp32, model.onnx ~310MB, có lexicon riêng) sang kokoro-int8-en-v0_19
// (chỉ tiếng Anh, 11 giọng, int8, model.int8.onnx ~86-103MB, KHÔNG cần
// lexicon). Do đó:
//   - Tên file model đổi thành "model.int8.onnx".
//   - Bỏ tham số "lexicon" khỏi OfflineTtsKokoroModelConfig — bản en-v0_19
//     không có file lexicon-us-en.txt/lexicon-gb-en.txt đi kèm.
//   - numSpeakers giờ là 11 (sid 0-10) thay vì 53 — nếu có UI/code khác
//     hardcode range speaker id cũ, cần rà lại riêng.
//
// ⚠️ currentSid (speaker id) — có thể đổi qua setSpeaker(sid), dùng tạm
// thời để thử nghiệm/so sánh các giọng khác nhau trong model. Mặc định vẫn
// là 0 để không đổi hành vi cũ nếu không ai gọi setSpeaker().
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
    private val generateMutex = Mutex()

    // Đếm số lần gọi speak() — dùng để lời gọi generate() cũ (đang đợi tới
    // lượt hoặc vừa generate xong sau khi bị "vượt mặt") tự biết mình đã lỗi
    // thời, không phát audio nữa dù generate() đã hoàn tất.
    private var latestRequestId = 0L

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

                // ⚠️ ĐÃ ĐỔI: model.int8.onnx (kokoro-int8-en-v0_19), không
                // còn tham số "lexicon" — bản chỉ tiếng Anh này không cần
                // file lexicon-us-en.txt/lexicon-gb-en.txt riêng.
                val kokoroConfig = OfflineTtsKokoroModelConfig(
                    model   = "$modelDir/model.int8.onnx",
                    voices  = "$modelDir/voices.bin",
                    tokens  = "$modelDir/tokens.txt",
                    dataDir = "$modelDir/espeak-ng-data",
                )

                val modelConfig = OfflineTtsModelConfig(
                    kokoro     = kokoroConfig,
                    numThreads = 2,
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

    // ── Đổi giọng đọc theo speaker id ────────────────────────────────────
    // Model kokoro-int8-en-v0_19 có 11 giọng: sid hợp lệ 0-10.
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