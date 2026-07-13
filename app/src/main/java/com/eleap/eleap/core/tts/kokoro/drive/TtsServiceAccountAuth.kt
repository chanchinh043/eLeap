// TtsServiceAccountAuth.kt
// Đặt tại: com/eleap/eleap/core/tts/kokoro/drive/TtsServiceAccountAuth.kt
// (chuyển từ core/tts/kokoro/ sang core/tts/kokoro/drive/ — logic không đổi,
// chỉ đổi package. Đây là chi tiết xác thực CỦA RIÊNG transport Drive, tách
// khỏi cấp kokoro/ để không lẫn với hợp đồng chung TtsKokoroPackSource/
// TtsKokoroPackManifest — 1 transport khác (vd S3) sẽ có cách xác thực
// hoàn toàn khác, nằm trong thư mục riêng của nó, vd kokoro/s3/)
//
// Tự thực hiện OAuth2 "JWT Bearer flow" cho Service Account — KHÔNG dùng
// thư viện google-auth-library (nặng, kéo nhiều dependency phụ), chỉ dùng
// java.security (có sẵn trong JDK/Android) để tự ký JWT bằng RS256, đúng
// tinh thần tối giản của dự án (singleton thủ công, không Hilt/DI).
//
// ── VÌ SAO CẦN FLOW NÀY (thay vì API key đơn thuần) ────────────────────────
// API key đơn thuần KHÔNG đủ quyền gọi files.list (search theo tên) trên
// Drive API, kể cả với thư mục share công khai — Google chặn từ ~2019 để
// chống scrape. Muốn search được, bắt buộc phải có access_token OAuth thật
// (không quan trọng end-user là ai, chỉ cần token hợp lệ). Service Account
// cho phép app tự "đăng nhập" bằng 1 private key nhúng sẵn, KHÔNG cần người
// dùng app phải tự đăng nhập Google — đánh đổi: lộ file JSON này (decompile
// APK) đồng nghĩa lộ quyền đọc vĩnh viễn đúng thư mục Drive đã share cho
// service account (đã giảm thiểu bằng cách CHỈ share đúng 1 thư mục TTS,
// không phải toàn bộ Drive).
//
// ── LUỒNG (theo đúng chuẩn RFC 7523 - JWT Bearer) ──────────────────────────
// 1. Đọc file assets/tts_service_account.json (client_email, private_key,
//    token_uri) — nếu không có file này, coi như CHƯA cấu hình, mọi hàm trả
//    về null, không throw (TtsKokoroConfig sẽ tự bỏ qua đăng ký nguồn).
// 2. Tự dựng JWT gồm 3 phần base64url nối bằng dấu '.':
//      header.claims.signature
//    - header: {"alg":"RS256","typ":"JWT"}
//    - claims: iss (client_email), scope (Drive readonly), aud (token_uri),
//      iat/exp (hiệu lực 1 giờ)
//    - signature: ký RS256 lên "header.claims" bằng private_key, dùng
//      java.security.Signature — đây là phần đáng lẽ cần thư viện ngoài,
//      nhưng JDK có sẵn đủ dùng.
// 3. POST JWT này tới token_uri (https://oauth2.googleapis.com/token) theo
//    form-urlencoded, nhận về access_token (sống mặc định 3600s).
// 4. Cache access_token trong RAM (Volatile, không cần DataStore/SharedPref
//    — token chỉ sống 1 giờ, mất khi kill app cũng không sao, tự xin lại),
//    tự làm mới sớm 60s trước khi hết hạn thật để tránh race lúc đang dùng
//    giữa chừng thì token hết hạn.
//
// Toàn bộ hàm đều KHÔNG throw ra ngoài — mọi lỗi (thiếu file, lỗi mạng, lỗi
// ký) đều log rồi trả về null, đúng hợp đồng "kokoro/ luôn là lưới an toàn
// tuỳ chọn, không bao giờ làm app crash" đã thống nhất từ đầu.
package com.eleap.eleap.core.tts.kokoro.drive

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec

private const val TAG = "TtsServiceAccountAuth"
private const val ASSET_FILE_NAME = "tts_service_account.json"

// Chỉ xin quyền ĐỌC Drive — không cần ghi/xoá gì cả, giữ phạm vi hẹp nhất
// có thể (nguyên tắc least privilege), dù rủi ro lộ file JSON vẫn tồn tại.
private const val SCOPE = "https://www.googleapis.com/auth/drive.readonly"
private const val EXPIRY_SECONDS = 3600L
private const val REFRESH_MARGIN_MS = 60_000L

private val json = Json { ignoreUnknownKeys = true }

@Serializable
private data class ServiceAccountJson(
    val client_email: String,
    val private_key: String,
    val token_uri: String,
)

@Serializable
private data class TokenResponse(
    val access_token: String,
    val expires_in: Long,
)

object TtsServiceAccountAuth {

    @Volatile
    private var cachedToken: String? = null

    @Volatile
    private var cachedExpiryAtMs: Long = 0L

    @Volatile
    private var serviceAccount: ServiceAccountJson? = null

    @Volatile
    private var loadAttempted = false

    // ── Dùng ở TtsKokoroConfig để quyết định có đăng ký TtsGoogleDriveSource
    // hay không — true nếu asset tồn tại và parse được đủ 3 trường cần thiết.
    fun isConfigured(context: Context): Boolean = loadServiceAccount(context) != null

    // ── Điểm gọi CHÍNH — trả về access_token còn hạn dùng, tự làm mới nếu
    // cần. null nếu chưa cấu hình HOẶC gọi mạng thất bại (mất mạng, Google
    // lỗi...) — caller (TtsGoogleDriveSource) tự hiểu là "không lấy được
    // token, coi như không có gì để tải", không throw.
    fun getAccessToken(context: Context): String? {
        val now = System.currentTimeMillis()
        cachedToken?.let { token ->
            if (now < cachedExpiryAtMs - REFRESH_MARGIN_MS) return token
        }
        return refreshToken(context)
    }

    private fun loadServiceAccount(context: Context): ServiceAccountJson? {
        serviceAccount?.let { return it }
        // Chỉ thử đọc file 1 lần duy nhất trong vòng đời process — nếu thiếu
        // file, không có lý do gì nó tự xuất hiện giữa chừng, tránh log rác
        // lặp lại mỗi lần app gọi getAccessToken().
        if (loadAttempted) return null
        loadAttempted = true
        return try {
            val text = context.assets.open(ASSET_FILE_NAME).bufferedReader().use { it.readText() }
            val parsed = json.decodeFromString(ServiceAccountJson.serializer(), text)
            serviceAccount = parsed
            parsed
        } catch (e: Exception) {
            Log.d(
                TAG,
                "loadServiceAccount: không đọc được assets/$ASSET_FILE_NAME, " +
                        "coi như chưa cấu hình nguồn tải remote",
                e
            )
            null
        }
    }

    private fun refreshToken(context: Context): String? {
        val account = loadServiceAccount(context) ?: return null
        return try {
            val jwt = buildSignedJwt(account)
            val response = requestAccessToken(account.token_uri, jwt) ?: return null
            cachedToken = response.access_token
            cachedExpiryAtMs = System.currentTimeMillis() + response.expires_in * 1000
            response.access_token
        } catch (e: Exception) {
            Log.e(TAG, "refreshToken: lỗi lấy access token", e)
            null
        }
    }

    // ── Tự dựng + ký JWT theo đúng RFC 7523 §3 (JWT Bearer Assertion) ──────
    private fun buildSignedJwt(account: ServiceAccountJson): String {
        val nowSec = System.currentTimeMillis() / 1000
        val header = """{"alg":"RS256","typ":"JWT"}"""
        val claims = "{" +
                "\"iss\":\"${account.client_email}\"," +
                "\"scope\":\"$SCOPE\"," +
                "\"aud\":\"${account.token_uri}\"," +
                "\"iat\":$nowSec," +
                "\"exp\":${nowSec + EXPIRY_SECONDS}" +
                "}"

        val encodedHeader = base64UrlEncode(header.toByteArray(Charsets.UTF_8))
        val encodedClaims = base64UrlEncode(claims.toByteArray(Charsets.UTF_8))
        val signingInput = "$encodedHeader.$encodedClaims"

        val privateKey = parsePrivateKey(account.private_key)
        val signatureBytes = Signature.getInstance("SHA256withRSA").apply {
            initSign(privateKey)
            update(signingInput.toByteArray(Charsets.UTF_8))
        }.sign()

        return "$signingInput.${base64UrlEncode(signatureBytes)}"
    }

    // ── private_key trong file JSON ở dạng PEM (có "-----BEGIN PRIVATE
    // KEY-----" và ký tự "\n" literal) — cần bóc ra đúng bytes DER rồi build
    // PrivateKey qua PKCS8EncodedKeySpec (chuẩn định dạng Google Service
    // Account luôn dùng, là PKCS#8, thuật toán RSA).
    private fun parsePrivateKey(pem: String): PrivateKey {
        val cleaned = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\n", "")
            .replace("\n", "")
            .trim()
        val keyBytes = Base64.decode(cleaned, Base64.DEFAULT)
        val keySpec = PKCS8EncodedKeySpec(keyBytes)
        return KeyFactory.getInstance("RSA").generatePrivate(keySpec)
    }

    // ── Đổi JWT lấy access_token thật — dùng HttpURLConnection có sẵn
    // trong Android SDK, không thêm dependency (OkHttp/Retrofit) chỉ để
    // gọi đúng 1 endpoint này.
    private fun requestAccessToken(tokenUri: String, jwt: String): TokenResponse? {
        val connection = URL(tokenUri).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000

        val body = "grant_type=" +
                URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", "UTF-8") +
                "&assertion=" + URLEncoder.encode(jwt, "UTF-8")

        return try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }
                Log.w(TAG, "requestAccessToken: HTTP ${connection.responseCode}, body=$errorBody")
                return null
            }

            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            json.decodeFromString(TokenResponse.serializer(), responseText)
        } catch (e: Exception) {
            Log.e(TAG, "requestAccessToken: lỗi gọi token_uri=$tokenUri", e)
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun base64UrlEncode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}