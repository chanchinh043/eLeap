// TtsRemoteConfig.kt
// Đặt tại: com/eleap/eleap/core/tts/remote/TtsRemoteConfig.kt
//
// ⚠️ ĐÃ ĐỔI SANG SERVICE ACCOUNT — không còn đọc BuildConfig.TTS_DRIVE_API_KEY
// nữa (xem TtsServiceAccountAuth.kt + TtsGoogleDriveSource.kt). Vẫn giữ
// rootFolderId qua BuildConfig như cũ vì giá trị này KHÔNG phải bí mật.
//
// Nơi DUY NHẤT quyết định có đăng ký TtsGoogleDriveSource vào
// TtsRemoteSourceRegistry hay không.
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
// 3. Thêm vào app/build.gradle.kts, trong khối `android { defaultConfig { ... } }`
//    (XOÁ đoạn buildConfigField cũ cho TTS_DRIVE_API_KEY nếu còn, không
//    dùng nữa):
//
//      buildConfigField(
//          "String", "TTS_DRIVE_ROOT_FOLDER_ID",
//          "\"${localProperties.getProperty("TTS_DRIVE_ROOT_FOLDER_ID", "")}\""
//      )
//
//    Và đảm bảo `buildFeatures { buildConfig = true }` đã bật.
//
// 4. Gọi TtsRemoteConfig.registerIfConfigured(context) MỘT LẦN ở
//    MainActivity.onCreate() — cùng chỗ với TtsReadingHistory.init(context)/
//    TtsVoiceSnapshot.init(context) hiện có (đã làm ở bước trước, không cần
//    sửa lại MainActivity.kt).
//
// ── AN TOÀN KHI CHƯA CẤU HÌNH XONG ─────────────────────────────────────
// Nếu THIẾU file assets/tts_service_account.json HOẶC rootFolderId còn rỗng
// → registerIfConfigured() tự BỎ QUA, không register gì cả — TtsRemotePackWorker
// sẽ tự coi là bình thường, không crash, chỉ dựa hoàn toàn vào pregen/ để tự
// sinh audio như trước.
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
            Log.d(
                TAG,
                "registerIfConfigured: chưa cấu hình TTS_DRIVE_ROOT_FOLDER_ID trong " +
                        "local.properties, bỏ qua đăng ký nguồn tải remote (pregen/ vẫn hoạt động bình thường)"
            )
            return
        }

        if (!TtsServiceAccountAuth.isConfigured(appContext)) {
            Log.d(
                TAG,
                "registerIfConfigured: thiếu assets/tts_service_account.json hoặc file không hợp lệ, " +
                        "bỏ qua đăng ký nguồn tải remote (pregen/ vẫn hoạt động bình thường)"
            )
            return
        }

        TtsRemoteSourceRegistry.register(TtsGoogleDriveSource(appContext, rootFolderId))
        Log.d(TAG, "registerIfConfigured: đã đăng ký TtsGoogleDriveSource (Service Account) làm nguồn tải remote")
    }
}