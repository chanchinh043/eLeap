// TtsPlaybackRouter.kt
// Đặt tại: com/eleap/eleap/core/tts/TtsPlaybackRouter.kt
//
// ⚠️ Đặt ở core/tts/ (KHÔNG phải core/tts/pregen/) — vì đây là CỬA NGÕ CHUNG
// cho UI (WordPopup/SentencePopup/PhrasePopup) gọi vào khi cần phát 1
// từ/câu/cụm từ, khác với các file trong pregen/ (chỉ phục vụ việc TẠO SẴN
// cache ở hậu trường, không liên quan trực tiếp tới hành động phát của
// người dùng). TtsPlaybackRouter là nơi DUY NHẤT quyết định "phát từ cache
// hay generate on-the-fly" — UI không cần biết gì về cơ chế cache bên dưới.
//
// Logic: nếu engine đang active là Kokoro (qua TtsVoiceSnapshot.currentTargetSid()
// — trả về null nếu KHÔNG phải Kokoro, đúng ý nghĩa đã chốt ở
// TtsVoiceSnapshot.kt) → tra cache ĐÚNG 1 sid hiện tại, phát THẲNG file cache
// tìm được (đúng hash) cho đúng (readingId, itemType, itemId). Ngược lại
// (chưa có cache, hoặc đang là Android TTS) → fallback y hệt hành vi CŨ: gọi
// TtsManager.speak(text) như trước khi có tính năng pre-cache, KHÔNG đổi gì
// ở nhánh này.
//
// ⚠️ SỬA (quay lại logic đơn giản — KHÔNG còn dò theo chuỗi giọng): trước
// đây khi cache giọng hiện tại chưa có, Router thử tra thêm các giọng cùng
// nhóm (TtsVoicePairing.fallbackChainOf()), vì TtsPregenWorker từng lưu
// audio "cứu" được vào cache RIÊNG của giọng thay thế. Giờ
// TtsPregenWorker.repairSilentItem() đã đổi cách lưu — audio cứu được luôn
// ghi ĐÈ thẳng vào cache của SID GỐC (xem TtsPregenWorker.kt) — nên cache
// của sid hiện tại luôn tự chứa đủ audio, Router không cần dò thêm giọng
// nào khác nữa.
//
// ⚠️ KHÔNG tái dùng cơ chế AudioTrack thô bên trong KokoroTtsEngine (vốn
// được viết riêng cho việc phát trực tiếp samples vừa generate xong, gắn
// chặt với luồng sinh audio) — dùng android.media.MediaPlayer để phát file
// .wav đã có sẵn trên đĩa: đơn giản, ít code hơn hẳn (không cần tự parse
// lại header WAV để lấy sampleRate/kênh như khi phát từ FloatArray thô),
// và đây đúng là use-case chuẩn của MediaPlayer (phát 1 file có sẵn), khác
// hẳn use-case của AudioTrack (phát PCM thô theo luồng ngay khi vừa sinh).
//
// Singleton thủ công, KHÔNG dùng Hilt/DI — cùng phong cách với TtsManager.
package com.eleap.eleap.core.tts

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
// ⚠️ MỚI: TtsAudioCache/TtsCacheItemType đã chuyển từ pregen/ sang package
// cache/ riêng (xem TtsAudioCache.kt) — TtsVoiceSnapshot vẫn ở pregen/ như cũ,
// không đổi.
import com.eleap.eleap.core.tts.cache.TtsAudioCache
import com.eleap.eleap.core.tts.cache.TtsCacheItemType
import com.eleap.eleap.core.tts.pregen.TtsVoiceSnapshot
import java.io.File

object TtsPlaybackRouter {

    private const val TAG = "TtsPlaybackRouter"

    // MediaPlayer đang phát file cache gần nhất (nếu có) — giữ lại để có
    // thể stop()/release() đúng lúc, tránh rò rỉ hoặc chồng tiếng nếu người
    // dùng bấm sang từ/câu/cụm khác trong khi file cũ chưa phát xong (cùng
    // tinh thần QUEUE_FLUSH của TtsEngine.speak() — luôn ngắt cái đang phát
    // dở trước khi phát cái mới).
    private var cachedPlayer: MediaPlayer? = null

    // ── Điểm gọi DUY NHẤT từ UI ──────────────────────────────────────────
    // context: cần để TtsAudioCache build đúng đường dẫn filesDir/tts_cache/...
    // text: nội dung gốc (tiếng Anh) — dùng để tính contentHash khi tra
    //       cache, VÀ dùng làm fallback nếu không có cache.
    // readingId/itemType/itemId: định danh chính xác item đang được đọc —
    //       lấy sẵn từ sentence/word/phrase đang có trong composable gọi
    //       tới, không cần truy vấn thêm gì mới (xem điểm chạm A đã chốt).
    fun speak(
        context: Context,
        text: String,
        readingId: String,
        itemType: TtsCacheItemType,
        itemId: String,
    ) {
        if (text.isBlank()) return

        // sid == null nghĩa là engine đang active KHÔNG phải Kokoro (đang
        // là Android TTS) — theo đúng ý nghĩa đã chốt ở
        // TtsVoiceSnapshot.currentTargetSid(), trường hợp này KHÔNG có gì
        // để tra cache, fallback thẳng luôn.
        val sid = TtsVoiceSnapshot.currentTargetSid()
        if (sid != null) {
            val hash = TtsAudioCache.contentHash(text)
            val cachedFile = TtsAudioCache.getCachedFile(context, readingId, sid, itemType, itemId, hash)
            if (cachedFile != null) {
                playCachedFile(cachedFile, fallbackText = text)
                return
            }
        }

        // Không có cache cho sid hiện tại (chưa kịp pre-cache/repair, hoặc
        // đang là Android TTS) — hành vi CŨ, giữ nguyên không đổi.
        stopCachedPlayback()
        TtsManager.speak(text)
    }

    // ── Dừng phát — gọi khi cần ngắt ngay (vd người dùng đóng popup) mà
    // không chờ audio hiện tại phát xong tự nhiên. Dừng CẢ HAI khả năng
    // đang phát: file cache (MediaPlayer riêng của Router) và engine of
    // TtsManager (Kokoro/Android TTS) — vì Router không tự biết lượt phát
    // gần nhất đã đi theo nhánh nào. ─────────────────────────────────────
    fun stop() {
        stopCachedPlayback()
        TtsManager.stop()
    }

    // ── Phát 1 file .wav có sẵn — dùng prepareAsync() (KHÔNG dùng
    // prepare() đồng bộ) để không block luồng gọi (thường là luồng UI, vd
    // trong LaunchedEffect) dù chỉ vài chục ms đọc header file. Nếu
    // prepare/start thất bại vì bất kỳ lý do gì (file hỏng, hiếm khi xảy ra
    // vì TtsAudioCache tự ghi đúng định dạng) → fallback sang
    // TtsManager.speak(fallbackText) để người dùng vẫn nghe được, không bị
    // im lặng hoàn toàn. ─────────────────────────────────────────────────
    private fun playCachedFile(file: File, fallbackText: String) {
        stopCachedPlayback()

        Log.d(TAG, "playCachedFile: phát file cache path=${file.absolutePath}")

        try {
            val player = MediaPlayer()
            cachedPlayer = player

            player.setDataSource(file.absolutePath)

            player.setOnPreparedListener { mp ->
                // Chỉ start nếu đây vẫn còn là player đang được Router theo
                // dõi — phòng trường hợp hiếm: giữa lúc prepareAsync() đang
                // chạy ngầm, người dùng đã bấm sang item khác khiến
                // stopCachedPlayback() được gọi (cachedPlayer đổi sang null
                // hoặc 1 player khác) TRƯỚC KHI callback này tới.
                if (cachedPlayer === mp) {
                    mp.start()
                }
            }

            player.setOnCompletionListener { mp ->
                mp.release()
                if (cachedPlayer === mp) {
                    cachedPlayer = null
                }
            }

            player.setOnErrorListener { mp, what, extra ->
                Log.e(TAG, "playCachedFile: lỗi MediaPlayer (what=$what, extra=$extra), fallback sang generate on-the-fly")
                mp.release()
                if (cachedPlayer === mp) {
                    cachedPlayer = null
                }
                TtsManager.speak(fallbackText)
                true // đã tự xử lý lỗi, không cần MediaPlayer gọi thêm callback khác
            }

            player.prepareAsync()
        } catch (e: Exception) {
            Log.e(TAG, "playCachedFile: lỗi khi phát file cache '${file.name}', fallback sang generate on-the-fly", e)
            cachedPlayer = null
            TtsManager.speak(fallbackText)
        }
    }

    private fun stopCachedPlayback() {
        cachedPlayer?.let { player ->
            try {
                if (player.isPlaying) {
                    player.stop()
                }
            } catch (e: Exception) {
                // isPlaying/stop() có thể ném lỗi nếu player đang ở trạng
                // thái "Error" hoặc chưa prepare xong — bỏ qua an toàn, mục
                // tiêu chỉ là đảm bảo release() chạy được ngay sau đây.
                Log.d(TAG, "stopCachedPlayback: bỏ qua lỗi khi stop() player cũ", e)
            }
            player.release()
        }
        cachedPlayer = null
    }
}