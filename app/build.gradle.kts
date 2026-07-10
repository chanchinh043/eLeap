import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        load(FileInputStream(localPropertiesFile))
    }
}

android {
    namespace = "com.eleap.eleap"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.eleap.eleap"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "String",
            "OPENAI_API_KEY",
            "\"${localProperties.getProperty("OPENAI_API_KEY", "")}\""
        )

        // rootFolderId của thư mục Drive chứa các gói giọng đọc (.zip) —
        // KHÔNG phải bí mật (chỉ là ID định danh thư mục, tự nó không cấp
        // quyền truy cập gì), nhưng vẫn đọc qua local.properties cho đồng
        // bộ cách làm với OPENAI_API_KEY, dễ đổi giữa các máy dev khác nhau
        // mà không phải sửa code. Xem TtsRemoteConfig.kt để biết cách dùng.
        buildConfigField(
            "String",
            "TTS_DRIVE_ROOT_FOLDER_ID",
            "\"${localProperties.getProperty("TTS_DRIVE_ROOT_FOLDER_ID", "")}\""
        )

        // Chỉ đóng gói .so cho arm64-v8a — quyết định đã chốt khi thêm
        // sherpa-onnx (Kokoro TTS on-device): không hỗ trợ máy 32-bit đời cũ
        // hay emulator x86, đổi lại giảm đáng kể size APK/AAB vì
        // libonnxruntime.so khá nặng (~20-40MB/kiến trúc). Nếu sau này cần
        // hỗ trợ thêm kiến trúc khác, sửa lại danh sách này VÀ copy thêm
        // đúng thư mục .so tương ứng vào app/src/main/jniLibs/.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Model Kokoro (.onnx) và voice data (.bin) trong assets/kokoro/ — KHÔNG
    // nén khi đóng gói APK/AAB. Nếu để mặc định nén, sherpa-onnx đọc file
    // trực tiếp từ đường dẫn trên disk (sau khi copy ra filesDir ở bước
    // KokoroTtsEngine sau này) có thể đọc sai/hỏng dữ liệu, hoặc chậm hơn
    // hẳn lúc copy vì phải giải nén trước.
    androidResources {
        noCompress += listOf("onnx", "bin")
    }
}

dependencies {
    // sherpa-onnx (Kokoro TTS on-device) — .aar để trực tiếp trong app/libs/,
    // KHÔNG qua Maven vì đây là bản build sẵn bạn tự tải về, không có trên
    // registry công khai nào. implementation(files(...)) đủ dùng cho 1 file
    // .aar đơn lẻ, không cần khai báo thêm flatDir repository.
    implementation(files("libs/sherpa-onnx-1.13.2.aar"))

    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // lifecycle-process — ProcessLifecycleOwner, dùng ở MainActivity để
    // bật/tắt SyncRealtime theo lifecycle của toàn app (foreground/background).
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Supabase (Auth + Postgrest) — singleton thủ công, KHÔNG qua Hilt
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)
    // Realtime — lắng nghe thay đổi bảng user_vocabulary qua WebSocket, để các
    // thiết bị khác biết ngay khi có create/xoá (core/sync/SyncRealtime.kt).
    implementation(libs.supabase.realtime)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlinx.serialization.json)

    // WorkManager — chạy sync nền (core/sync/SyncWorker.kt)
    implementation(libs.androidx.work.runtime.ktx)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}