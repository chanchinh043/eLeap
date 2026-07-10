// TtsRemoteSourceRegistry.kt
// Đặt tại: com/eleap/eleap/core/tts/remote/TtsRemoteSourceRegistry.kt
//
// Chỗ DUY NHẤT giữ tham chiếu tới TtsRemoteSource đang được cấu hình — để
// TtsRemotePackWorker không cần biết TRƯỚC nguồn cụ thể là gì (Google Drive
// hay nguồn khác, xem bước sau). Nơi khởi tạo app (MainActivity.onCreate(),
// cùng chỗ TtsManager.init()) sẽ gọi register(...) đúng 1 lần với impl cụ
// thể đã chọn.
//
// source == null nghĩa là CHƯA cấu hình nguồn tải nào (vd tính năng này
// chưa bật, hoặc đang phát triển dở — như hiện tại, bước 10 chưa làm) — mọi
// nơi gọi tới đây (Worker) phải tự coi đây là tình huống BÌNH THƯỜNG, không
// phải lỗi: đơn giản là "không có gì để tải", để pregen/ tự sinh audio như
// thiết kế cũ.
//
// Singleton thủ công, KHÔNG dùng Hilt/DI — cùng phong cách với TtsManager.
package com.eleap.eleap.core.tts.remote

object TtsRemoteSourceRegistry {

    @Volatile
    private var source: TtsRemoteSource? = null

    // Gọi 1 lần lúc khởi tạo app, sau khi đã chọn xong impl cụ thể (vd
    // TtsGoogleDriveSource ở bước 10). An toàn gọi lại nhiều lần — ghi đè
    // đơn giản, không có tác dụng phụ.
    fun register(newSource: TtsRemoteSource) {
        source = newSource
    }

    // TtsRemotePackWorker gọi hàm này mỗi lần chạy — trả về null nếu chưa
    // từng register(), Worker tự hiểu là "chưa bật tính năng tải từ xa".
    fun current(): TtsRemoteSource? = source
}