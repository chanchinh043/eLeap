// AssetCopier.kt
// Đặt tại: com/eleap/eleap/core/tts/AssetCopier.kt
//
// Copy đệ quy 1 thư mục assets (vd "kokoro") ra filesDir — sherpa-onnx cần
// đường dẫn file THẬT trên đĩa để load model, không đọc trực tiếp được từ
// assets/ nén trong APK (dù đã noCompress ở build.gradle.kts, AssetManager
// vẫn không cấp được 1 "đường dẫn file" thật cho code C++ bên dưới sherpa-onnx
// dùng open()/fopen() trực tiếp).
//
// Chỉ copy nếu CHƯA copy đủ — dùng 1 file đánh dấu ".copied" ở thư mục đích,
// tránh copy lại tốn thời gian mỗi lần mở app (model khá nặng, vài chục MB).
package com.eleap.eleap.core.tts

import android.content.Context
import android.util.Log
import java.io.File

private const val TAG = "AssetCopier"

object AssetCopier {

    // Copy toàn bộ thư mục assets/{assetDir} ra {context.filesDir}/{assetDir}.
    // Trả về đường dẫn tuyệt đối của thư mục đích sau khi copy xong (hoặc đã
    // có sẵn từ trước) — dùng thẳng đường dẫn này để trỏ modelDir cho
    // sherpa-onnx.
    fun copyAssetDirIfNeeded(context: Context, assetDir: String): String {
        val destDir = File(context.filesDir, assetDir)
        val markerFile = File(destDir, ".copied")

        if (markerFile.exists()) {
            Log.d(TAG, "copyAssetDirIfNeeded: '$assetDir' đã copy từ trước, bỏ qua")
            return destDir.absolutePath
        }

        Log.d(TAG, "copyAssetDirIfNeeded: bắt đầu copy '$assetDir' → ${destDir.absolutePath}")
        val startTime = System.currentTimeMillis()

        // Xoá sạch thư mục đích trước nếu có (vd lần copy trước bị dở dang,
        // thiếu marker file) — đảm bảo không lẫn file cũ/thiếu file mới.
        if (destDir.exists()) {
            destDir.deleteRecursively()
        }
        destDir.mkdirs()

        copyAssetDirRecursive(context, assetDir, destDir)

        // Tạo marker file SAU KHI copy xong toàn bộ — nếu app bị kill giữa
        // chừng lúc đang copy, marker sẽ không tồn tại, lần mở app kế tiếp
        // sẽ tự copy lại từ đầu (an toàn, dù tốn thêm thời gian 1 lần nữa).
        markerFile.writeText(System.currentTimeMillis().toString())

        val elapsed = System.currentTimeMillis() - startTime
        Log.d(TAG, "copyAssetDirIfNeeded: copy xong '$assetDir' trong ${elapsed}ms")

        return destDir.absolutePath
    }

    private fun copyAssetDirRecursive(context: Context, assetPath: String, destDir: File) {
        val assetManager = context.assets
        val children = assetManager.list(assetPath) ?: return

        if (children.isEmpty()) {
            // Là file lá (không có con) — copy trực tiếp.
            copyAssetFile(context, assetPath, File(destDir.parentFile, destDir.name))
            return
        }

        destDir.mkdirs()

        for (child in children) {
            val childAssetPath = "$assetPath/$child"
            val childDest = File(destDir, child)

            // Thư mục con thật sự sẽ có list() khác rỗng; file lá sẽ trả về
            // mảng rỗng khi gọi list() trên chính đường dẫn của nó.
            val grandChildren = assetManager.list(childAssetPath)
            if (grandChildren != null && grandChildren.isNotEmpty()) {
                copyAssetDirRecursive(context, childAssetPath, childDest)
            } else {
                copyAssetFile(context, childAssetPath, childDest)
            }
        }
    }

    private fun copyAssetFile(context: Context, assetPath: String, destFile: File) {
        destFile.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            destFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }
}