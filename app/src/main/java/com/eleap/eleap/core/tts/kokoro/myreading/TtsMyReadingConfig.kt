// TtsMyReadingConfig.kt
// Đặt tại: com/eleap/eleap/core/tts/kokoro/myreading/TtsMyReadingConfig.kt
//
// Nơi DUY NHẤT đọc cấu hình cho TtsMyReadingRequestClient — cùng tinh thần
// TtsKokoroConfig.kt (nơi DUY NHẤT quyết định đăng ký TtsGoogleDriveSource),
// nhưng phạm vi hẹp hơn nhiều: ở đây KHÔNG có service account/OAuth gì cả,
// server MyReading TTS là 1 backend riêng của chính app (không phải Google
// API), chỉ cần 1 base URL.
//
// ⚠️ PHẠM VI: config này CHỈ dành cho luồng "xin server tổng hợp TTS cho
// MyReading" (TtsMyReadingRequestClient) — KHÔNG liên quan gì tới
// TtsKokoroConfig (đó là wiring cho Drive, vẫn dùng CHUNG 1 folder Drive
// hiện tại cho cả bài hệ thống lẫn MyReading, xem quyết định đã chốt: kết
// quả server tổng hợp xong vẫn upload vào ĐÚNG folder Drive Kokoro đang
// dùng — TtsGoogleDriveSource/TtsKokoroPackDownloader không cần biết gì về
// config này).
//
// ── CẤU HÌNH CẦN THÊM (làm 1 lần, thủ công) ────────────────────────────
//
// 1. Thêm 1 dòng vào local.properties (đã có sẵn trong .gitignore mặc định
//    của Android Studio, KHÔNG commit):
//      TTS_MYREADING_API_BASE_URL=https://tts-myreading.eleap.example.com
//    (KHÔNG có dấu "/" ở cuối — mọi path ghép ở TtsMyReadingRequestClient
//    đều tự thêm "/" trước, vd "$baseUrl/tts/myreading/request".)
//
// 2. Thêm vào app/build.gradle.kts, trong khối
//    `android { defaultConfig { ... } }` (cạnh dòng buildConfigField của
//    TTS_DRIVE_ROOT_FOLDER_ID đã có sẵn, xem TtsKokoroConfig.kt):
//
//      buildConfigField(
//          "String", "TTS_MYREADING_API_BASE_URL",
//          "\"${localProperties.getProperty("TTS_MYREADING_API_BASE_URL", "")}\""
//      )
//
//    `buildFeatures { buildConfig = true }` đã bật sẵn từ trước (dùng chung
//    cho TTS_DRIVE_ROOT_FOLDER_ID), không cần bật lại.
//
// ── AN TOÀN KHI CHƯA CẤU HÌNH XONG ─────────────────────────────────────
// Nếu THIẾU dòng trên trong local.properties → baseUrl() trả về null,
// TtsMyReadingSyncTrigger tự bỏ qua việc xin tổng hợp TTS (chỉ log, không
// crash) — bài MyReading vẫn hoạt động bình thường, chỉ đơn giản là không
// có audio pre-cache cho MyReading, mọi lượt phát dùng Android TTS fallback
// (giống hệt hành vi khi TtsKokoroConfig chưa cấu hình xong).
package com.eleap.eleap.core.tts.kokoro.myreading

import android.util.Log
import com.eleap.eleap.BuildConfig

private const val TAG = "TtsMyReadingConfig"

object TtsMyReadingConfig {

    // Chỉ log cảnh báo "chưa cấu hình" 1 LẦN DUY NHẤT trong vòng đời process
    // — tránh log rác lặp lại mỗi lần có bài MyReading vừa sync xong (có thể
    // xảy ra khá thường xuyên), cùng tinh thần loadAttempted ở
    // TtsServiceAccountAuth.kt.
    @Volatile
    private var hasWarnedMissingConfig = false

    // ── Điểm gọi CHÍNH — trả về base URL đã cấu hình, hoặc null nếu chưa
    // cấu hình (rỗng/chưa điền local.properties). KHÔNG throw, KHÔNG crash —
    // caller (TtsMyReadingSyncTrigger) tự hiểu null là "bỏ qua tính năng
    // này", đúng nguyên tắc lưới an toàn tuỳ chọn của core/tts/.
    fun baseUrl(): String? {
        val configured = BuildConfig.TTS_MYREADING_API_BASE_URL
        if (configured.isBlank()) {
            if (!hasWarnedMissingConfig) {
                hasWarnedMissingConfig = true
                Log.w(
                    TAG,
                    "baseUrl: chưa cấu hình TTS_MYREADING_API_BASE_URL trong local.properties — " +
                            "sẽ KHÔNG xin server tổng hợp TTS cho bài MyReading nào, mọi lượt phát " +
                            "MyReading sẽ fallback Android TTS cho tới khi có audio pre-cache (nếu có " +
                            "từ nguồn khác) hoặc cấu hình được điền đủ."
                )
            }
            return null
        }
        // Chuẩn hoá: bỏ dấu "/" ở cuối nếu người cấu hình lỡ điền thừa, để
        // mọi nơi ghép path ("$baseUrl/tts/myreading/...") không bị "//"
        // kép — lỗi vặt dễ xảy ra khi copy-paste URL từ trình duyệt.
        return configured.trimEnd('/')
    }
}