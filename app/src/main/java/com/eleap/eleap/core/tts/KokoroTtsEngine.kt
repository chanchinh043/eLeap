// KokoroTtsEngine.kt
// Đặt tại: com/eleap/eleap/core/tts/KokoroTtsEngine.kt
//
// Bọc sherpa-onnx (OfflineTts, model Kokoro int8) — implement TtsEngine để
// TtsManager dùng chung interface với AndroidTtsEngine.
//
// ⚠️ TÊN CLASS/PACKAGE CỦA SHERPA-ONNX: import bên dưới dựa theo API phổ
// biến nhất của sherpa-onnx-kotlin (package com.k2fsa.sherpa.onnx). Nếu bản
// .aar bạn dùng đặt tên khác (từng xảy ra ở project này với Supabase — xem
// ghi chú tương tự ở SyncRealtime.kt/MyReadingSyncApi.kt), dùng autocomplete
// của Android Studio để sửa lại đúng tên class/field/method — cấu trúc
// tổng thể (OfflineTtsConfig → OfflineTtsModelConfig → OfflineTtsKokoroModelConfig)
// và luồng gọi (generate() → AudioTrack) vẫn giữ nguyên.
//
// Model KHÔNG đọc trực tiếp từ assets/ (xem AssetCopier.kt) — phải copy ra
// filesDir trước vì code C++ bên dưới cần đường dẫn file thật trên đĩa.
//
// KHÔNG phải singleton — TtsManager tự giữ 1 instance của class này.
//
// ⚠️ ĐÃ XÁC NHẬN (qua debug trên emulator x86_64 dùng ndk_translation):
// generate() bị TREO VĨNH VIỄN trên emulator dùng lớp dịch nhị phân ARM→x86
// (xác nhận qua RAW TEST bằng Thread thuần, độc lập hoàn toàn khỏi Mutex/
// coroutine của class này, và thử nhiều sid khác nhau đều treo giống hệt).
// Đây là giới hạn môi trường emulator, KHÔNG phải bug logic — code này chạy
// bình thường trên thiết bị Android thật (arm64), không cần sửa gì thêm.
//
// ── DEBUG CÒN GIỮ LẠI (tuỳ ý xoá sau khi xác nhận ổn định trên máy thật) ──
// 1) watchdog log mỗi 2 giây trong lúc chờ generate() — vô hại, tự dừng
//    ngay khi generate() trả về bình thường.
// 2) ghi thêm 1 file debug_tts_output.wav ra filesDir mỗi lần generate()
//    xong, để có thể kiểm tra độc lập kết quả âm thanh nếu cần soi lại.
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
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class KokoroTtsEngine : TtsEngine {

    private val TAG = "KokoroTtsEngine"

    private var tts: OfflineTts? = null
    private var isReadyFlag = false

    // ── DEBUG: giữ lại context để có filesDir ghi file WAV debug. ──────────
    private var appContext: Context? = null

    // speed truyền cho generate(): 1.0 = bình thường, giống ngữ nghĩa
    // currentRate mà TtsManager đang dùng cho AndroidTtsEngine — không cần
    // quy đổi gì thêm khi TtsManager gọi setSpeechRate() xuống đây.
    private var currentSpeed: Float = 1.0f

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

        // ── DEBUG: lưu context để dùng filesDir khi ghi WAV debug ──────────
        appContext = context.applicationContext

        engineScope.launch {
            try {
                // Copy asset ra filesDir (chỉ copy thật sự ở lần đầu tiên,
                // các lần sau tự bỏ qua nhờ marker file — xem AssetCopier.kt).
                val modelDir = AssetCopier.copyAssetDirIfNeeded(context, "kokoro")

                val kokoroConfig = OfflineTtsKokoroModelConfig(
                    // ⚠️ Tên file khớp đúng gói kokoro-multi-lang-v1_0 (release
                    // "tts-models" của sherpa-onnx) — "model.onnx"/"voices.bin",
                    // KHÔNG phải "kokoro-v1.0.int8.onnx"/"voices-v1.0.bin" của
                    // 1 số gói Kokoro khác đã lỗi thời/không khớp version.
                    model   = "$modelDir/model.onnx",
                    voices  = "$modelDir/voices.bin",
                    tokens  = "$modelDir/tokens.txt",
                    // 2 file lexicon (US + GB), phân cách bằng dấu phẩy —
                    // đúng cách sherpa-onnx expect nhiều lexicon cho Kokoro.
                    lexicon = "$modelDir/lexicon-us-en.txt,$modelDir/lexicon-gb-en.txt",
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

                Log.d(TAG, "init: Kokoro sẵn sàng, sampleRate=${tts?.sampleRate()}")
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

        // Tăng requestId NGAY LẬP TỨC (đồng bộ, trước khi launch coroutine)
        // — đây là "vé xếp hàng" mới nhất. Lời gọi generate() cũ (nếu đang
        // chạy dở, không thể bị ngắt thật sự) khi xong sẽ so lại requestId
        // của chính nó với latestRequestId; nếu không còn khớp (đã có người
        // bấm từ mới hơn) thì tự bỏ qua, không phát audio cũ chồng lên.
        val myRequestId = ++latestRequestId
        stopPlayback()

        currentJob = engineScope.launch {
            // ── DEBUG: watchdog log — in ra mỗi 2 giây nếu generate() vẫn
            // chưa xong. Trên máy thật, generate() dự kiến chỉ mất vài trăm
            // ms tới vài giây — watchdog này gần như sẽ không bao giờ kịp in
            // ra lần nào trước khi bị huỷ. Giữ lại như 1 lưới an toàn để
            // phát hiện sớm nếu có bất thường, có thể xoá sau khi yên tâm. ──
            val watchdog = launch {
                var waited = 0
                while (true) {
                    kotlinx.coroutines.delay(2000)
                    waited += 2
                    Log.w(TAG, "speak: [watchdog] vẫn đang chờ generate() cho \"$text\" — đã ${waited}s (requestId=$myRequestId)")
                }
            }

            // Đợi tới lượt — nếu có generate() khác đang chạy (kể cả của
            // request cũ hơn, không thể huỷ giữa chừng), lời gọi này CHỜ ở
            // đây thay vì gọi song song vào cùng 1 OfflineTts instance.
            generateMutex.withLock {
                // Sau khi đã lấy được lock, kiểm tra lại: nếu trong lúc chờ
                // đã có request MỚI HƠN xuất hiện (người dùng bấm liên tục
                // nhiều từ), request hiện tại đã lỗi thời — bỏ qua, không
                // generate audio sẽ không dùng tới.
                if (myRequestId != latestRequestId) {
                    Log.d(TAG, "speak: \"$text\" đã lỗi thời (có request mới hơn), bỏ qua")
                    watchdog.cancel()
                    return@withLock
                }

                try {
                    val engine = tts
                    if (engine == null) {
                        watchdog.cancel()
                        return@withLock
                    }
                    Log.d(TAG, "speak: bắt đầu generate() cho \"$text\" (requestId=$myRequestId)")
                    val startTime = System.currentTimeMillis()
                    val audio = engine.generate(text = text, sid = 0, speed = currentSpeed)
                    val elapsed = System.currentTimeMillis() - startTime

                    // Tới được đây nghĩa là generate() đã THỰC SỰ trả về —
                    // dừng watchdog ngay vì không cần cảnh báo nữa.
                    watchdog.cancel()

                    Log.d(
                        TAG,
                        "speak: generate() xong trong ${elapsed}ms (requestId=$myRequestId), " +
                                "samples=${audio.samples.size}, sampleRate=${audio.sampleRate}"
                    )

                    // ── DEBUG: ghi thẳng ra file WAV, KHÔNG qua AudioTrack —
                    // để kiểm tra độc lập xem generate() có sinh đúng dữ liệu
                    // âm thanh hay không. Có thể xoá khối try/catch này sau
                    // khi đã xác nhận Kokoro chạy ổn định trên máy thật. ──────
                    try {
                        val ctx = appContext
                        if (ctx != null) {
                            val debugFile = File(ctx.filesDir, "debug_tts_output.wav")
                            saveWav(audio.samples, audio.sampleRate, debugFile)
                            Log.d(TAG, "speak: [DEBUG] đã lưu WAV vào ${debugFile.absolutePath} (${debugFile.length()} bytes)")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "speak: [DEBUG] lỗi khi lưu WAV debug", e)
                    }

                    // Lại kiểm tra lần nữa SAU KHI generate() xong (có thể mất
                    // vài giây) — nếu đã có request mới hơn xuất hiện trong
                    // lúc generate() đang chạy dở, không phát audio này nữa.
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
                    watchdog.cancel()
                    Log.e(TAG, "speak: lỗi khi sinh/phát audio cho \"$text\"", e)
                }
            }
        }
    }

    // ── DEBUG: ghi mảng PCM float (mono, [-1.0, 1.0]) ra file WAV 16-bit PCM
    // chuẩn — không phụ thuộc hàm .save() có sẵn hay không trong bản .aar
    // đang dùng. Có thể xoá hàm này sau khi đã xác nhận Kokoro chạy ổn định
    // trên máy thật và không cần soi lại kết quả âm thanh nữa.
    private fun saveWav(samples: FloatArray, sampleRate: Int, file: File) {
        val pcm16 = ShortArray(samples.size) { i ->
            (samples[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }
        val dataSize = pcm16.size * 2
        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)

        buffer.put("RIFF".toByteArray())
        buffer.putInt(36 + dataSize)
        buffer.put("WAVE".toByteArray())

        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)               // độ dài phần fmt
        buffer.putShort(1)              // audio format = 1 (PCM)
        buffer.putShort(1)              // số kênh = 1 (mono)
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * 2)   // byte rate = sampleRate * numChannels * bytesPerSample
        buffer.putShort(2)              // block align = numChannels * bytesPerSample
        buffer.putShort(16)             // bits per sample

        buffer.put("data".toByteArray())
        buffer.putInt(dataSize)
        for (sample in pcm16) {
            buffer.putShort(sample)
        }

        FileOutputStream(file).use { out ->
            out.write(buffer.array())
        }
    }

    // Phát mảng PCM float (mono) đã sinh ra — dùng MODE_STATIC vì toàn bộ
    // audio đã có sẵn trong RAM sau generate(), không cần streaming từng
    // chunk. Với câu RẤT dài (nhiều đoạn văn), cân nhắc chuyển sang
    // generateWithCallback() + MODE_STREAM ở bản sau để giảm độ trễ trước
    // khi bắt đầu phát — bỏ qua ở bước này để giữ đơn giản trước.
    private fun playAudio(samples: FloatArray, sampleRate: Int) {
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        val bufferSizeBytes = maxOf(minBufferSize, samples.size * 4)
        Log.d(TAG, "playAudio: minBufferSize=$minBufferSize, bufferSizeBytes=$bufferSizeBytes")

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

        Log.d(TAG, "playAudio: AudioTrack state=${track.state} (1=INITIALIZED, 0=UNINITIALIZED)")

        audioTrack = track
        val framesWritten = track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
        Log.d(TAG, "playAudio: đã write $framesWritten/${samples.size} frames vào track")
        track.play()
        Log.d(TAG, "playAudio: đã gọi play(), playState=${track.playState} (3=PLAYING)")
    }

    private fun stopPlayback() {
        audioTrack?.let {
            try {
                it.stop()
                it.release()
            } catch (e: Exception) {
                // Track đã stop/release từ trước (vd đã phát xong tự nhiên) — bỏ qua.
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