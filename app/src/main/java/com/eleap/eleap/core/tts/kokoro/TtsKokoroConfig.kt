// TtsKokoroConfig.kt
// Đặt tại: com/eleap/eleap/core/tts/kokoro/TtsKokoroConfig.kt
//
// Nơi DUY NHẤT quyết định có đăng ký TtsGoogleDriveSource vào
// TtsKokoroPackSourceRegistry hay không.
//
// ⚠️ PHẠM VI: file này CHỈ lo wiring cho transport của RIÊNG Kokoro — không
// phải "config chung" cho mọi nhà cung cấp giọng đọc trong app. Nhà cung
// cấp khác (vd 1 dịch vụ TTS on-demand) sẽ có file config riêng của nó
// trong thư mục riêng (vd core/tts/google_cloud/TtsGoogleCloudConfig.kt),
// tự quyết định cách cấu hình/đăng ký theo đúng nhu cầu của nó — không đi
// qua đây, không có 1 "TtsConfig" trung tâm nào cố biết hết mọi vendor.
//
// ⚠️ CHỌN TRANSPORT Ở ĐÂY: hiện Kokoro chỉ có 1 transport (Drive, xem
// kokoro/drive/). Khi thêm transport thứ 2 (vd S3), đây là NƠI DUY NHẤT cần
// sửa để đổi/chọn transport nào đang active — TtsKokoroPackDownloader và
// mọi phần còn lại của Kokoro không cần biết/không cần đổi gì.
//
// ⚠️ QUAN TRỌNG (kiến trúc remote-only của Kokoro): app KHÔNG có khả năng
// tự sinh audio Kokoro on-device làm lưới an toàn — nếu file này KHÔNG
// đăng ký được transport (thiếu cấu hình/lỗi), audio pre-cache của giọng
// Kokoro sẽ KHÔNG BAO GIỜ có, mọi lượt phát dùng giọng Kokoro sẽ fallback
// thẳng sang Android TTS hệ thống (xem TtsPlaybackRouter.kt). Vì vậy
// registerIfConfigured() PHẢI được gọi đúng ở MainActivity.onCreate(), và
// cấu hình (service account + rootFolderId) PHẢI đúng trước khi phát hành.
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
// 4. Gọi TtsKokoroConfig.registerIfConfigured(context) MỘT LẦN ở
//    MainActivity.onCreate() — cùng chỗ với TtsManager.init(this) và
//    TtsVoiceSnapshot.init(this).
//
// ── AN TOÀN KHI CHƯA CẤU HÌNH XONG ─────────────────────────────────────
// Nếu THIẾU file assets/tts_service_account.json HOẶC rootFolderId còn rỗng
// → registerIfConfigured() tự BỎ QUA, không register gì cả, không crash —
// nhưng đây KHÔNG còn là trạng thái "chấp nhận được lâu dài" nữa (vì Kokoro
// không còn engine tự sinh làm lưới an toàn), chỉ nên xảy ra tạm thời lúc
// dev/debug.
package com.eleap.eleap.core.tts.kokoro

import android.content.Context
import android.util.Log
import com.eleap.eleap.BuildConfig
import com.eleap.eleap.core.tts.kokoro.drive.TtsGoogleDriveSource
import com.eleap.eleap.core.tts.kokoro.drive.TtsServiceAccountAuth

private const val TAG = "TtsKokoroConfig"

object TtsKokoroConfig {

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
                        "local.properties — sẽ KHÔNG có audio pre-cache cho giọng Kokoro, " +
                        "mọi lượt phát dùng giọng Kokoro sẽ fallback Android TTS"
            )
            return
        }

        if (!TtsServiceAccountAuth.isConfigured(appContext)) {
            Log.w(
                TAG,
                "registerIfConfigured: thiếu assets/tts_service_account.json hoặc file không hợp lệ — " +
                        "sẽ KHÔNG có audio pre-cache cho giọng Kokoro, mọi lượt phát dùng giọng Kokoro " +
                        "sẽ fallback Android TTS"
            )
            return
        }

        TtsKokoroPackSourceRegistry.register(TtsGoogleDriveSource(appContext, rootFolderId))
        Log.d(TAG, "registerIfConfigured: đã đăng ký TtsGoogleDriveSource (Service Account) làm transport cho Kokoro")
    }
}