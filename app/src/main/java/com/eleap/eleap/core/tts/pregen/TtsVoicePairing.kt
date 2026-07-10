// TtsVoicePairing.kt
// Đặt tại: com/eleap/eleap/core/tts/pregen/TtsVoicePairing.kt
//
// ── VÌ SAO CẦN FILE NÀY ──────────────────────────────────────────────────
// Model Kokoro (kokoro-multi-lang-v1_0, 53 giọng) thỉnh thoảng generate ra
// audio câm (NaN) cho MỘT SỐ từ ngắn ở MỘT sid cụ thể — đã xác nhận qua log
// thực tế (vd "the"/"at"/"its" ở sid=3 tức af_heart). Cùng 1 từ, đổi sang
// sid khác thường sẽ ra tiếng bình thường (khác instance model/embedding
// giọng nói, không cùng đường tính toán number gây NaN).
//
// Thay vì tự động "đổi giọng người dùng đang nghe" (ảnh hưởng trải nghiệm,
// người dùng không chọn thế), giải pháp chốt là: định nghĩa TRƯỚC các NHÓM
// giọng "thay thế cho nhau" — khi giọng A liên tục lỗi ở 1 item cụ thể,
// lần lượt dùng CÁC giọng còn lại trong CÙNG NHÓM để generate audio CHO
// ĐÚNG ITEM ĐÓ, rồi lưu vào cache CỦA GIỌNG ĐÓ. Người dùng vẫn đang nghe
// giọng A cho toàn bộ bài, chỉ riêng đúng từ/cụm bị lỗi đó là phát bằng
// giọng khác trong nhóm (khác giọng 1 chút, nhưng còn hơn im lặng hoàn
// toàn).
//
// ⚠️ SỬA (mở rộng từ 2 giọng/cặp → 3 giọng/nhóm): trước đây mỗi giọng chỉ
// có ĐÚNG 1 giọng thay thế (quan hệ 2 chiều 1-1: A↔B). Giờ mỗi giọng nằm
// trong 1 NHÓM 3 GIỌNG — khi 1 giọng bị câm liên tục, thử LẦN LƯỢT 2 giọng
// còn lại trong nhóm (không phải chỉ 1), theo đúng THỨ TỰ cố định đã khai
// báo trong nhóm. Xem TtsPregenWorker.repairSilentItem() để biết quy trình
// dùng danh sách trả về từ fallbackChainOf() như thế nào (mỗi giọng thử tối
// đa TtsCacheAuditor.MAX_ATTEMPTS_PER_VOICE lần, dừng sớm nếu hết câm).
//
// ── CHỌN NHÓM THEO GIỚI TÍNH/NGỮ ĐIỆU GẦN NHAU ───────────────────────────
// Nhóm NỮ: af_bella (sid=2) → af_heart (sid=3) → af_sarah (sid=9) — cả 3
// đều là giọng NỮ Mỹ (af_*).
// Nhóm NAM: am_adam (sid=11) → bm_george (sid=26) → am_michael (sid=16) —
// đều là giọng NAM (am_*/bm_* — chấp nhận khác accent Mỹ/Anh 1 chút giữa
// george và 2 giọng còn lại, vì đây chỉ là fallback hiếm khi dùng tới,
// không phải giọng chính).
//
// sid tra từ tài liệu chính thức của sherpa-onnx cho đúng model
// kokoro-multi-lang-v1_0 (khớp numSpeakers=53 trong log init của app):
// https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/kokoro.html
//
// ── CHỈ ÁP DỤNG CHO 2 NHÓM NÀY ────────────────────────────────────────────
// Các sid KHÔNG nằm trong 2 nhóm bên dưới → fallbackChainOf() trả về danh
// sách RỖNG → TtsPregenWorker/TtsPlaybackRouter tự hiểu "không có giọng
// thay thế", chỉ thử đúng giọng hiện tại rồi để nguyên (chờ audit sau).
//
// Singleton thủ công, KHÔNG dùng Hilt/DI — cùng phong cách các file khác
// trong package pregen/.
package com.eleap.eleap.core.tts.pregen

object TtsVoicePairing {

    // ── Tên gọi rõ ràng cho từng sid — dùng để LOG dễ đọc (không bắt buộc
    // logic phải dùng tới, chỉ để debug dễ hơn khi xem logcat). ─────────────
    const val SID_AF_BELLA = 2
    const val SID_AF_HEART = 3
    const val SID_AF_SARAH = 9
    const val SID_AM_ADAM = 11
    const val SID_AM_MICHAEL = 16
    const val SID_BM_GEORGE = 26

    // ── Khai báo các NHÓM 3 giọng — THỨ TỰ trong mỗi danh sách chính là thứ
    // tự ưu tiên thử fallback (vd nhóm nữ: nếu bella đang lỗi, thử heart
    // trước, sarah sau; nếu heart đang lỗi, thử bella trước, sarah sau —
    // xem generateFallbackChains() bên dưới để biết cách suy ra đúng thứ tự
    // cho TỪNG sid xuất phát, không chỉ 1 chiều cố định). ───────────────────
    private val GROUPS: List<List<Int>> = listOf(
        listOf(SID_AF_BELLA, SID_AF_HEART, SID_AF_SARAH),   // nhóm nữ
        listOf(SID_AM_ADAM, SID_BM_GEORGE, SID_AM_MICHAEL), // nhóm nam
    )

    // ── Bảng tra cứu: sid → danh sách fallback (2 giọng còn lại trong CÙNG
    // nhóm, giữ nguyên thứ tự khai báo trong GROUPS, bỏ đúng phần tử là
    // chính sid đó). Build 1 lần khi class load — mọi lời gọi
    // fallbackChainOf() sau đó chỉ là tra map, không tính toán lại. ────────
    private val fallbackChainMap: Map<Int, List<Int>> = buildMap {
        for (group in GROUPS) {
            for (sid in group) {
                put(sid, group.filter { it != sid })
            }
        }
    }

    // ── Điểm gọi DUY NHẤT từ nơi khác — trả về danh sách sid thay thế (theo
    // đúng thứ tự nên thử) nếu `sid` nằm trong 1 nhóm đã định nghĩa, danh
    // sách RỖNG nếu không (nghĩa là không có giọng thay thế cho sid này).
    // TtsPregenWorker duyệt qua danh sách này SAU KHI đã thử hết
    // MAX_ATTEMPTS_PER_VOICE lần ở giọng gốc mà vẫn câm. ────────────────────
    fun fallbackChainOf(sid: Int): List<Int> = fallbackChainMap[sid].orEmpty()

    // ── Tên hiển thị cho log — không bắt buộc, chỉ để log dễ đọc hơn số
    // sid trần trụi. Trả về "sid=$sid" nếu không nằm trong danh sách đã biết
    // tên (vd các giọng khác ngoài 6 giọng này vẫn generate bình thường,
    // không cần tên riêng). ─────────────────────────────────────────────────
    fun displayName(sid: Int): String = when (sid) {
        SID_AF_BELLA -> "af_bella(sid=$sid)"
        SID_AF_HEART -> "af_heart(sid=$sid)"
        SID_AF_SARAH -> "af_sarah(sid=$sid)"
        SID_AM_ADAM -> "am_adam(sid=$sid)"
        SID_AM_MICHAEL -> "am_michael(sid=$sid)"
        SID_BM_GEORGE -> "bm_george(sid=$sid)"
        else -> "sid=$sid"
    }
}