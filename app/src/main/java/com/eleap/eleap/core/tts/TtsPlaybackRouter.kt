// TtsPlaybackRouter.kt
// Đặt tại: com/eleap/eleap/core/tts/TtsPlaybackRouter.kt
//
// CỬA NGÕ CHUNG cho UI (WordPopup/SentencePopup/PhrasePopup) gọi vào khi cần
// phát 1 từ/câu/cụm từ — nơi DUY NHẤT quyết định "phát từ cache đã tải sẵn
// hay fallback sang Android TTS". UI không cần biết gì về cơ chế cache/
// remote bên dưới.
//
// Logic: tra cache theo (readingId, sid hiện tại từ TtsVoiceSnapshot,
// itemType, itemId, contentHash của text). Có cache → phát THẲNG file đó
// bằng MediaPlayer. KHÔNG có cache (chưa tải kịp, mất mạng, chưa cấu hình
// nguồn remote...) → fallback sang TtsManager.speak(text) — Android TTS hệ
// thống, luôn sẵn sàng, không phụ thuộc mạng.
//
// ⚠️ KHÁC thiết kế cũ (khi còn Kokoro): trước đây nhánh "không có cache" còn
// có thể generate on-the-fly bằng Kokoro trước khi rơi xuống Android TTS.
// Giờ chỉ còn đúng 1 fallback — Android TTS — nên nhánh này đơn giản hơn
// nhiều.
//
// Dùng android.media.MediaPlayer để phát file .ogg đã có sẵn trên đĩa — đơn
// giản, đúng use-case chuẩn của MediaPlayer (phát 1 file có sẵn).
//
// Singleton thủ công, KHÔNG dùng Hilt/DI — cùng phong cách với TtsManager.
package com.eleap.eleap.core.tts

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import com.eleap.eleap.core.tts.cache.TtsAudioCache
import com.eleap.eleap.core.tts.cache.TtsCacheItemType
import java.io.File

object TtsPlaybackRouter {

    private const val TAG = "TtsPlaybackRouter"

    // MediaPlayer đang phát file cache gần nhất (nếu có) — giữ lại để có
    // thể stop()/release() đúng lúc, tránh rò rỉ hoặc chồng tiếng nếu người
    // dùng bấm sang từ/câu/cụm khác trong khi file cũ chưa phát xong (cùng
    // tinh thần QUEUE_FLUSH của TtsManager.speak() — luôn ngắt cái đang phát
    // dở trước khi phát cái mới).
    private var cachedPlayer: MediaPlayer? = null

    // ── Điểm gọi DUY NHẤT từ UI ──────────────────────────────────────────
    // context: cần để TtsAudioCache build đúng đường dẫn filesDir/tts_cache/...
    // text: nội dung gốc (tiếng Anh) — dùng để tính contentHash khi tra
    //       cache, VÀ dùng làm fallback nếu không có cache.
    // readingId/itemType/itemId: định danh chính xác item đang được đọc —
    //       lấy sẵn từ sentence/word/phrase đang có trong composable gọi
    //       tới.
    fun speak(
        context: Context,
        text: String,
        readingId: String,
        itemType: TtsCacheItemType,
        itemId: String,
    ) {
        if (text.isBlank()) return

        val sid = TtsVoiceSnapshot.currentSid()
        val hash = TtsAudioCache.contentHash(text)
        val cachedFile = TtsAudioCache.getCachedFile(context, readingId, sid, itemType, itemId, hash)

        if (cachedFile != null) {
            playCachedFile(cachedFile, fallbackText = text)
            return
        }

        // Không có cache (chưa tải kịp, mất mạng, chưa cấu hình nguồn remote,
        // hoặc server chưa build gói cho bài này) — fallback Android TTS.
        stopCachedPlayback()
        TtsManager.speak(text)
    }

    // ── Dừng phát — gọi khi cần ngắt ngay (vd người dùng đóng popup) mà
    // không chờ audio hiện tại phát xong tự nhiên. Dừng CẢ HAI khả năng
    // đang phát: file cache (MediaPlayer riêng của Router) và Android TTS
    // (TtsManager) — vì Router không tự biết lượt phát gần nhất đã đi theo
    // nhánh nào. ─────────────────────────────────────────────────────────
    fun stop() {
        stopCachedPlayback()
        TtsManager.stop()
    }

    // ── Phát 1 file .ogg có sẵn — dùng prepareAsync() (KHÔNG dùng
    // prepare() đồng bộ) để không block luồng gọi (thường là luồng UI, vd
    // trong LaunchedEffect) dù chỉ vài chục ms đọc header file. Nếu
    // prepare/start thất bại vì bất kỳ lý do gì (file hỏng, hiếm khi xảy ra
    // vì file tải nguyên vẹn từ Drive có xác thực checksum) → fallback sang
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
                Log.e(TAG, "playCachedFile: lỗi MediaPlayer (what=$what, extra=$extra), fallback sang Android TTS")
                mp.release()
                if (cachedPlayer === mp) {
                    cachedPlayer = null
                }
                TtsManager.speak(fallbackText)
                true // đã tự xử lý lỗi, không cần MediaPlayer gọi thêm callback khác
            }

            player.prepareAsync()
        } catch (e: Exception) {
            Log.e(TAG, "playCachedFile: lỗi khi phát file cache '${file.name}', fallback sang Android TTS", e)
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