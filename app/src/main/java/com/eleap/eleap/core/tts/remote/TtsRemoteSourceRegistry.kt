// TtsRemoteSourceRegistry.kt
// Đặt tại: com/eleap/eleap/core/tts/remote/TtsRemoteSourceRegistry.kt
//
// Chỗ DUY NHẤT giữ tham chiếu tới TtsRemoteSource đang được cấu hình — để
// TtsRemotePackWorker/TtsRemotePackDownloader không cần biết TRƯỚC nguồn cụ
// thể là gì (Google Drive hay nguồn khác). Nơi khởi tạo app
// (MainActivity.onCreate()) sẽ gọi register(...) đúng 1 lần với impl cụ thể
// đã chọn (xem TtsRemoteConfig.kt).
//
// source == null nghĩa là CHƯA cấu hình nguồn tải nào — mọi nơi gọi tới đây
// phải tự coi đây là tình huống BÌNH THƯỜNG, không phải lỗi: đơn giản là
// "không có gì để tải". ⚠️ LƯU Ý: vì app không còn tự sinh audio (đã bỏ
// Kokoro), nếu source == null thì audio pre-cache sẽ KHÔNG BAO GIỜ có —
// TtsPlaybackRouter sẽ luôn fallback sang Android TTS hệ thống cho mọi lượt
// phát. Đây là lý do TtsRemoteConfig.registerIfConfigured() BẮT BUỘC phải
// chạy đúng, không được bỏ sót ở MainActivity.
//
// Singleton thủ công, KHÔNG dùng Hilt/DI — cùng phong cách với TtsManager.
package com.eleap.eleap.core.tts.remote

object TtsRemoteSourceRegistry {

    @Volatile
    private var source: TtsRemoteSource? = null

    // Gọi 1 lần lúc khởi tạo app, sau khi đã chọn xong impl cụ thể (vd
    // TtsGoogleDriveSource). An toàn gọi lại nhiều lần — ghi đè đơn giản,
    // không có tác dụng phụ.
    fun register(newSource: TtsRemoteSource) {
        source = newSource
    }

    // TtsRemotePackWorker/TtsPlaybackRouter gọi hàm này mỗi khi cần — trả về
    // null nếu chưa từng register(), caller tự hiểu là "chưa cấu hình nguồn
    // tải nào".
    fun current(): TtsRemoteSource? = source
}