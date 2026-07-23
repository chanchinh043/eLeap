// TtsPlaybackRouter.kt
// Đặt tại: com/eleap/eleap/core/tts/TtsPlaybackRouter.kt
// (đã cập nhật cho kiến trúc đa-vendor: TtsVoiceSnapshot giờ nhớ CẢ vendor
// lẫn sid, không chỉ sid như bản cũ — xem TtsVoiceSnapshot.kt)
//
// CỬA NGÕ CHUNG cho UI (WordPopup/SentencePopup/PhrasePopup) gọi vào khi cần
// phát 1 từ/câu/cụm từ — nơi DUY NHẤT quyết định "phát từ cache đã tải sẵn
// hay fallback sang Android TTS". UI không cần biết gì về cơ chế cache/
// vendor cụ thể bên dưới (Kokoro hay bất kỳ nhà cung cấp nào khác).
//
// Logic: tra cache theo (readingId, vendor + sid hiện tại từ
// TtsVoiceSnapshot, itemType, itemId, contentHash của text). Có cache →
// phát THẲNG file đó bằng ExoPlayer (xem ghi chú ⚠️ ENGINE PHÁT bên dưới).
// KHÔNG có cache (chưa tải kịp, mất mạng, chưa cấu hình transport của vendor
// đang chọn, server chưa build gói cho bài này...) → fallback sang
// TtsManager.speak(text) — Android TTS hệ thống, luôn sẵn sàng, không phụ
// thuộc mạng, không phụ thuộc vendor.
//
// ⚠️ Router KHÔNG quan tâm vendor đang chọn là gì khi tra cache — chỉ cần
// truyền đúng vendor vào TtsAudioCache.getCachedFile(), hàm đó tự biết build
// đúng path {readingId}/{vendor}/{sid}/... Router cũng KHÔNG tự biết đuôi
// file (.ogg/.mp3/...) — ExoPlayer tự nhận diện định dạng qua nội dung file,
// mỗi vendor có thể dùng định dạng khác nhau mà Router không cần đổi gì.
//
// ⚠️ ENGINE PHÁT — DÙNG androidx.media3 ExoPlayer, KHÔNG DÙNG android.media.
// MediaPlayer (đã đổi từ MediaPlayer sang ExoPlayer, xem lý do dưới):
// android.media.MediaPlayer (qua NuPlayer bên dưới) khi đổi tốc độ phát
// (PlaybackParams.setSpeed() < 1.0) KHÔNG xả (flush) hết phần sample còn
// nằm trong bộ đệm time-stretch nội bộ trước khi báo hết bài (EOS) — với
// file audio NGẮN như 1 từ/câu/cụm của TTS (gần như không có khoảng lặng
// đệm ở cuối), hậu quả là MẤT THẬT vài chục-vài trăm ms cuối file, thường
// rơi đúng vào phụ âm cuối/âm gió (năng lượng thấp, nằm sát mép cuối) —
// càng giảm tốc độ (vd 0.6x) càng mất nhiều. ExoPlayer's AudioProcessor
// (cũng dùng thuật toán time-stretch kiểu Sonic để giữ nguyên pitch) xử lý
// ĐÚNG bước "drain" — xả hết sample còn đệm lại trước khi coi như phát
// xong, không bị lỗi mất âm này ở bất kỳ tốc độ nào.
//
// ⚠️ TỐC ĐỘ ĐỌC (speechRate) — DÙNG CHUNG 1 NGUỒN GIỮA CACHE VÀ FALLBACK:
// file cache được server build ở tốc độ chuẩn (1.0x), nhưng khi PHÁT, Router
// tự áp lại tốc độ hiện tại (TtsManager.getSpeechRate() — cùng giá trị nút
// "R" ở ReadingScreen đang chỉnh cho Android TTS) lên ExoPlayer qua
// PlaybackParameters(speed, pitch). PHẢI luôn truyền pitch=1.0f — đây là
// điểm mấu chốt để đổi tốc độ mà KHÔNG làm giọng bị cao/thấp giọng đi
// (không "méo tiếng" kiểu chipmunk).
//
// ⚠️ RÀNG BUỘC THREAD: ExoPlayer PHẢI được tạo/gọi từ ĐÚNG 1 thread cố định
// (mặc định là thread đã gọi Builder().build() — ở đây LUÔN là main thread,
// vì mọi lời gọi speak() hiện tại đều xuất phát từ LaunchedEffect trong
// Compose, chạy trên Dispatchers.Main). KHÔNG gọi speak()/stop() từ
// background thread nếu sau này có thêm caller mới — nếu cần, phải tự
// post lên main thread trước khi gọi vào Router.
//
// Singleton thủ công, KHÔNG dùng Hilt/DI — cùng phong cách với TtsManager.
package com.eleap.eleap.core.tts

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.eleap.eleap.core.tts.cache.TtsAudioCache
import com.eleap.eleap.core.tts.cache.TtsCacheItemType
import java.io.File

object TtsPlaybackRouter {

    private const val TAG = "TtsPlaybackRouter"

    // ExoPlayer đang phát file cache gần nhất (nếu có) — giữ lại để có thể
    // stop()/release() đúng lúc, tránh rò rỉ hoặc chồng tiếng nếu người dùng
    // bấm sang từ/câu/cụm khác trong khi file cũ chưa phát xong (cùng tinh
    // thần QUEUE_FLUSH của TtsManager.speak() — luôn ngắt cái đang phát dở
    // trước khi phát cái mới).
    private var cachedPlayer: ExoPlayer? = null

    // ── Điểm gọi DUY NHẤT từ UI ──────────────────────────────────────────
    // context: cần để TtsAudioCache build đúng đường dẫn filesDir/tts_cache/...
    //       VÀ để tạo ExoPlayer (LUÔN dùng context.applicationContext khi
    //       build, tránh giữ tham chiếu Activity ngắn hạn — xem
    //       playCachedFile()).
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

        // ── Đọc CẢ vendor lẫn sid từ TtsVoiceSnapshot — khác bản cũ chỉ
        // đọc currentSid() (khi đó sid coi như mặc định luôn thuộc Kokoro).
        // Giờ sid CHỈ có ý nghĩa trong phạm vi 1 vendor cụ thể (2 vendor
        // khác nhau có thể trùng số sid mà không liên quan gì tới nhau),
        // nên PHẢI truyền đúng cả 2 khi tra cache. ──────────────────────
        val vendor = TtsVoiceSnapshot.currentVendor()
        val sid = TtsVoiceSnapshot.currentSid()
        val hash = TtsAudioCache.contentHash(text)
        val cachedFile = TtsAudioCache.getCachedFile(context, readingId, vendor, sid, itemType, itemId, hash)

        if (cachedFile != null) {
            playCachedFile(context, cachedFile, fallbackText = text)
            return
        }

        // Không có cache (chưa tải kịp, mất mạng, chưa cấu hình transport
        // của vendor đang chọn, hoặc server chưa build gói cho bài này) —
        // fallback Android TTS. Nhánh này giống hệt nhau bất kể vendor đang
        // chọn là gì — Android TTS không quan tâm/không biết gì về khái
        // niệm vendor/sid.
        stopCachedPlayback()
        TtsManager.speak(text)
    }

    // ── Dừng phát — gọi khi cần ngắt ngay (vd người dùng đóng popup) mà
    // không chờ audio hiện tại phát xong tự nhiên. Dừng CẢ HAI khả năng
    // đang phát: file cache (ExoPlayer riêng của Router) và Android TTS
    // (TtsManager) — vì Router không tự biết lượt phát gần nhất đã đi theo
    // nhánh nào. ─────────────────────────────────────────────────────────
    fun stop() {
        stopCachedPlayback()
        TtsManager.stop()
    }

    // ── Phát 1 file audio có sẵn bằng ExoPlayer — tạo 1 instance MỚI cho
    // MỖI lượt phát (giống hành vi MediaPlayer cũ), KHÔNG tái dùng player
    // giữa các lượt: đơn giản hoá vòng đời (release() ngay khi xong/lỗi,
    // không cần lo trạng thái cũ còn sót), và tránh phải quản lý listener
    // đăng ký/gỡ liên tục trên 1 instance sống lâu. Chi phí tạo mới
    // ExoPlayer cho audio ngắn (1 từ/câu/cụm) là không đáng kể.
    //
    // Nếu prepare/play thất bại vì bất kỳ lý do gì (file hỏng, hiếm khi xảy
    // ra vì file tải nguyên vẹn từ nguồn có xác thực checksum, tuỳ vendor)
    // → fallback sang TtsManager.speak(fallbackText) để người dùng vẫn nghe
    // được, không bị im lặng hoàn toàn. ──────────────────────────────────
    private fun playCachedFile(context: Context, file: File, fallbackText: String) {
        stopCachedPlayback()

        Log.d(TAG, "playCachedFile: phát file cache path=${file.absolutePath}")

        try {
            val player = ExoPlayer.Builder(context.applicationContext).build()
            cachedPlayer = player

            // ── Áp tốc độ đọc hiện tại (nút "R" ở ReadingScreen) — LUÔN
            // truyền pitch=1.0f, đây là điểm mấu chốt để đổi tốc độ mà
            // KHÔNG làm giọng bị méo/cao-thấp đi (xem ghi chú ⚠️ TỐC ĐỘ ĐỌC
            // ở đầu file). Set TRƯỚC khi play() — không bắt buộc với
            // ExoPlayer (khác MediaPlayer, có thể set bất kỳ lúc nào kể cả
            // đang phát), nhưng set sớm cho nhất quán, dễ đọc.
            val rate = TtsManager.getSpeechRate()
            player.playbackParameters = PlaybackParameters(rate, 1.0f)

            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        if (cachedPlayer === player) {
                            cachedPlayer = null
                        }
                        player.release()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e(TAG, "playCachedFile: lỗi ExoPlayer, fallback sang Android TTS", error)
                    if (cachedPlayer === player) {
                        cachedPlayer = null
                    }
                    player.release()
                    TtsManager.speak(fallbackText)
                }
            })

            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            player.prepare()
            player.playWhenReady = true
        } catch (e: Exception) {
            Log.e(TAG, "playCachedFile: lỗi khi phát file cache '${file.name}', fallback sang Android TTS", e)
            cachedPlayer?.release()
            cachedPlayer = null
            TtsManager.speak(fallbackText)
        }
    }

    private fun stopCachedPlayback() {
        cachedPlayer?.let { player ->
            try {
                player.stop()
            } catch (e: Exception) {
                // Bỏ qua an toàn nếu player đang ở trạng thái lỗi/chưa
                // prepare xong — mục tiêu chỉ là đảm bảo release() chạy
                // được ngay sau đây.
                Log.d(TAG, "stopCachedPlayback: bỏ qua lỗi khi stop() player cũ", e)
            }
            player.release()
        }
        cachedPlayer = null
    }
}