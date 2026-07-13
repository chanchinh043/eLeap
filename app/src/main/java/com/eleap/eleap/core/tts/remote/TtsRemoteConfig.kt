// TtsRemoteConfig.kt
// Đặt tại: com/eleap/eleap/core/tts/remote/TtsRemoteConfig.kt
//
// Nơi DUY NHẤT quyết định có đăng ký TtsGoogleDriveSource vào
// TtsRemoteSourceRegistry hay không.
//
// ⚠️ QUAN TRỌNG (khác thiết kế cũ khi còn Kokoro): app KHÔNG còn khả năng tự
// sinh audio làm lưới an toàn nữa — nếu file này KHÔNG đăng ký được nguồn
// (thiếu cấu hình/lỗi), toàn bộ audio pre-cache sẽ KHÔNG BAO GIỜ có, mọi
// lượt phát sẽ fallback thẳng sang Android TTS hệ thống (xem
// TtsPlaybackRouter.kt). Vì vậy registerIfConfigured() PHẢI được gọi đúng ở
// MainActivity.onCreate(), và cấu hình (service account + rootFolderId)
// PHẢI đúng trước khi phát hành.
//
// ── CẤU HÌNH CẦN THÊM (làm 1 lần, thủ công) ────────────────────────────
//
// 1. Copy file JSON service account (tải từ Google Cloud Console) vào:
//      app/src/main/assets/tts_service_account.json
//    và thêm dòng sau vào .gitignore (file chứa private_key, KHÔNG commit):
//      app/src/main/assets/tts_service_account.json
//
// 2. Thêm 1 dòng vào local.properties (file này đã có sẵn trong
//    .gitignore mặc định của Android Studio):
//      TTS_DRIVE_ROOT_FOLDER_ID=1a2B3c...folder_id_thật...
//
// 3. Thêm vào app/build.gradle.kts, trong khối `android { defaultConfig { ... } }`:
//
//      buildConfigField(
//          "String", "TTS_DRIVE_ROOT_FOLDER_ID",
//          "\"${localProperties.getProperty("TTS_DRIVE_ROOT_FOLDER_ID", "")}\""
//      )
//
//    Và đảm bảo `buildFeatures { buildConfig = true }` đã bật.
//
// 4. Gọi TtsRemoteConfig.registerIfConfigured(context) MỘT LẦN ở
//    MainActivity.onCreate() — cùng chỗ với TtsManager.init(this).
//
// ── AN TOÀN KHI CHƯA CẤU HÌNH XONG ─────────────────────────────────────
// Nếu THIẾU file assets/tts_service_account.json HOẶC rootFolderId còn rỗng
// → registerIfConfigured() tự BỎ QUA, không register gì cả, không crash —
// nhưng khác thiết kế cũ, đây KHÔNG còn là trạng thái "chấp nhận được lâu
// dài" nữa, chỉ nên xảy ra tạm thời lúc dev/debug.
package com.eleap.eleap.core.tts.remote

import android.content.Context
import android.util.Log
import com.eleap.eleap.BuildConfig

private const val TAG = "TtsRemoteConfig"

object TtsRemoteConfig {

    // Gọi 1 lần ở MainActivity.onCreate() — an toàn gọi lại nhiều lần.
    // LUÔN truyền applicationContext (context.applicationContext) để tránh
    // giữ tham chiếu tới Activity ngắn hạn trong 1 object sống suốt vòng
    // đời app.
    fun registerIfConfigured(context: Context) {
        val appContext = context.applicationContext
        val rootFolderId = BuildConfig.TTS_DRIVE_ROOT_FOLDER_ID

        if (rootFolderId.isBlank()) {
            Log.w(
                TAG,
                "registerIfConfigured: chưa cấu hình TTS_DRIVE_ROOT_FOLDER_ID trong " +
                        "local.properties — sẽ KHÔNG có audio pre-cache, mọi lượt phát fallback Android TTS"
            )
            return
        }

        if (!TtsServiceAccountAuth.isConfigured(appContext)) {
            Log.w(
                TAG,
                "registerIfConfigured: thiếu assets/tts_service_account.json hoặc file không hợp lệ — " +
                        "sẽ KHÔNG có audio pre-cache, mọi lượt phát fallback Android TTS"
            )
            return
        }

        TtsRemoteSourceRegistry.register(TtsGoogleDriveSource(appContext, rootFolderId))
        Log.d(TAG, "registerIfConfigured: đã đăng ký TtsGoogleDriveSource (Service Account) làm nguồn tải remote")
    }
}