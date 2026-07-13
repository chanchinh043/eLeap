// TtsVoiceCatalog.kt
// Đặt tại: com/eleap/eleap/core/tts/TtsVoiceCatalog.kt
//
// Danh mục CÁC GIỌNG ĐỌC người dùng có thể chọn — tách riêng khỏi UI (màn
// chọn giọng, sẽ làm ở bước sau) và khỏi TtsVoiceSnapshot (chỉ lo việc
// "nhớ sid nào đang chọn", không biết/không cần biết sid đó tên gì thuộc
// hãng nào). File này CHỈ có DỮ LIỆU + hàm tra cứu thuần tuý, không có
// logic tải/phát/lưu trạng thái nào cả.
//
// ⚠️ VÌ SAO TÁCH RIÊNG (thay vì hardcode thẳng vào UI): dự tính SAU NÀY sẽ
// thêm giọng từ nhà cung cấp khác (Google Cloud TTS, Amazon Polly...) song
// song với Kokoro — khi đó chỉ cần thêm 1 danh sách TtsVoiceOption mới ứng
// với vendor mới vào allVoices bên dưới, KHÔNG cần sửa màn chọn giọng
// (UI chỉ lặp qua allVoices, không quan tâm vendor cụ thể là gì) và KHÔNG
// cần sửa TtsPlaybackRouter/TtsAudioCache (vẫn chỉ dùng sid như trước).
//
// ⚠️ LƯU Ý CHO TƯƠNG LAI (chưa xử lý ở bước này, chỉ ghi chú lại): sid hiện
// là 1 số Int DÙNG CHUNG làm tên thư mục cache
// (tts_cache/{readingId}/{sid}/...) VÀ làm khoá trong tên file .zip trên
// Drive ("{readingId}_{sid}.zip", xem TtsGoogleDriveSource.kt). Khi thêm
// vendor thứ 2, PHẢI đảm bảo sid không đụng nhau giữa 2 vendor (vd Google
// TTS không được dùng lại đúng số 0-52 mà Kokoro đang chiếm) — cách đơn
// giản nhất là đánh số tiếp (Google TTS bắt đầu từ sid=100 chẳng hạn), coi
// như 1 dải số riêng, không cần đổi cấu trúc path/tên file nào cả.
//
// Nguồn danh sách 53 giọng Kokoro (kokoro-multi-lang-v1_0, sid 0-52):
// https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/kokoro.html
// Quy ước tên: ký tự đầu = ngôn ngữ (a=Anh-Mỹ, b=Anh-Anh, e=Tây Ban Nha,
// f=Pháp, h=Hindi, i=Ý, j=Nhật, p=Bồ Đào Nha/Brazil, z=Trung), ký tự thứ 2 =
// giới tính (f=nữ, m=nam).
package com.eleap.eleap.core.tts

// ── Nhà cung cấp giọng — mở rộng dần khi thêm hãng mới (Google TTS...).
// Hiện tại chỉ có KOKORO vì server pipeline mới chỉ build gói cho model này. ─
enum class TtsVoiceVendor {
    KOKORO,
}

// ── 1 lựa chọn giọng cụ thể mà người dùng có thể chọn ở màn chọn giọng ───────
// sid: khớp CHÍNH XÁC với sid mà server dùng để đặt tên file .zip trên Drive
//      và với thư mục cache local (xem TtsAudioCache.voiceDir()).
// voiceName: tên gốc theo quy ước của vendor (vd "af_bella") — không hiển thị
//      trực tiếp cho người dùng, chỉ để đối chiếu/debug.
// languageTag: mã ngôn ngữ kiểu BCP-47 rút gọn (vd "en-US") — dùng để lọc
//      (vd chỉ lấy giọng tiếng Anh cho tính năng đọc bài).
// displayName: tên hiển thị ở UI, đã dịch sẵn sang tiếng Việt.
data class TtsVoiceOption(
    val sid: Int,
    val vendor: TtsVoiceVendor,
    val voiceName: String,
    val languageTag: String,
    val displayName: String,
)

object TtsVoiceCatalog {

    // ── Nhãn ngôn ngữ hiển thị tiếng Việt, tra theo languageTag. ────────────
    private fun accentLabel(languageTag: String): String = when (languageTag) {
        "en-US" -> "Anh-Mỹ"
        "en-GB" -> "Anh-Anh"
        "es"    -> "Tây Ban Nha"
        "fr-FR" -> "Pháp"
        "hi"    -> "Hindi"
        "it"    -> "Ý"
        "ja"    -> "Nhật"
        "pt-BR" -> "Bồ Đào Nha (Brazil)"
        "zh"    -> "Trung"
        else    -> languageTag
    }

    // ── Giới tính suy ra từ ký tự thứ 2 trong voiceName (quy ước Kokoro:
    // "af_..." = nữ, "am_..." = nam, tương tự cho các tiền tố ngôn ngữ khác). ─
    private fun genderLabel(voiceName: String): String = when (voiceName.getOrNull(1)) {
        'f' -> "Nữ"
        'm' -> "Nam"
        else -> ""
    }

    // ── Build displayName tự động từ voiceName + languageTag — tránh phải tự
    // gõ tay 53 tên hiển thị (dễ gõ sai/gõ thiếu), chỉ cần khai báo đúng cặp
    // (sid, voiceName, languageTag) ở danh sách bên dưới. ───────────────────
    private fun buildVoice(sid: Int, voiceName: String, languageTag: String): TtsVoiceOption {
        val shortName = voiceName.substringAfter('_').replaceFirstChar { it.uppercase() }
        val gender = genderLabel(voiceName)
        val accent = accentLabel(languageTag)
        val displayName = if (gender.isNotBlank()) "$shortName ($gender, $accent)" else "$shortName ($accent)"
        return TtsVoiceOption(
            sid = sid,
            vendor = TtsVoiceVendor.KOKORO,
            voiceName = voiceName,
            languageTag = languageTag,
            displayName = displayName,
        )
    }

    // ── Toàn bộ 53 giọng Kokoro (kokoro-multi-lang-v1_0), sid 0-52 — ĐÚNG
    // khớp với sid server dùng để build gói .zip trên Drive, KHÔNG được tự ý
    // đổi số ở đây nếu không đổi tương ứng ở pipeline server. ────────────────
    private val KOKORO_VOICES: List<TtsVoiceOption> = listOf(
        buildVoice(0, "af_alloy", "en-US"),
        buildVoice(1, "af_aoede", "en-US"),
        buildVoice(2, "af_bella", "en-US"),
        buildVoice(3, "af_heart", "en-US"),
        buildVoice(4, "af_jessica", "en-US"),
        buildVoice(5, "af_kore", "en-US"),
        buildVoice(6, "af_nicole", "en-US"),
        buildVoice(7, "af_nova", "en-US"),
        buildVoice(8, "af_river", "en-US"),
        buildVoice(9, "af_sarah", "en-US"),
        buildVoice(10, "af_sky", "en-US"),
        buildVoice(11, "am_adam", "en-US"),
        buildVoice(12, "am_echo", "en-US"),
        buildVoice(13, "am_eric", "en-US"),
        buildVoice(14, "am_fenrir", "en-US"),
        buildVoice(15, "am_liam", "en-US"),
        buildVoice(16, "am_michael", "en-US"),
        buildVoice(17, "am_onyx", "en-US"),
        buildVoice(18, "am_puck", "en-US"),
        buildVoice(19, "am_santa", "en-US"),
        buildVoice(20, "bf_alice", "en-GB"),
        buildVoice(21, "bf_emma", "en-GB"),
        buildVoice(22, "bf_isabella", "en-GB"),
        buildVoice(23, "bf_lily", "en-GB"),
        buildVoice(24, "bm_daniel", "en-GB"),
        buildVoice(25, "bm_fable", "en-GB"),
        buildVoice(26, "bm_george", "en-GB"),
        buildVoice(27, "bm_lewis", "en-GB"),
        buildVoice(28, "ef_dora", "es"),
        buildVoice(29, "em_alex", "es"),
        buildVoice(30, "ff_siwis", "fr-FR"),
        buildVoice(31, "hf_alpha", "hi"),
        buildVoice(32, "hf_beta", "hi"),
        buildVoice(33, "hm_omega", "hi"),
        buildVoice(34, "hm_psi", "hi"),
        buildVoice(35, "if_sara", "it"),
        buildVoice(36, "im_nicola", "it"),
        buildVoice(37, "jf_alpha", "ja"),
        buildVoice(38, "jf_gongitsune", "ja"),
        buildVoice(39, "jf_nezumi", "ja"),
        buildVoice(40, "jf_tebukuro", "ja"),
        buildVoice(41, "jm_kumo", "ja"),
        buildVoice(42, "pf_dora", "pt-BR"),
        buildVoice(43, "pm_alex", "pt-BR"),
        buildVoice(44, "pm_santa", "pt-BR"),
        buildVoice(45, "zf_xiaobei", "zh"),
        buildVoice(46, "zf_xiaoni", "zh"),
        buildVoice(47, "zf_xiaoxiao", "zh"),
        buildVoice(48, "zf_xiaoyi", "zh"),
        buildVoice(49, "zm_yunjian", "zh"),
        buildVoice(50, "zm_yunxi", "zh"),
        buildVoice(51, "zm_yunxia", "zh"),
        buildVoice(52, "zm_yunyang", "zh"),
    )

    // ── Điểm gọi CHÍNH cho màn chọn giọng — TOÀN BỘ giọng từ MỌI vendor đã
    // khai báo. Khi thêm vendor mới (vd Google TTS), chỉ cần cộng thêm danh
    // sách mới vào đây (vd KOKORO_VOICES + GOOGLE_VOICES). ───────────────────
    val allVoices: List<TtsVoiceOption> = KOKORO_VOICES

    // ── Chỉ giọng tiếng Anh — eLeap hiện chỉ dạy tiếng Anh nên màn chọn
    // giọng nhiều khả năng chỉ cần dùng danh sách này (28 giọng, sid 0-27)
    // thay vì allVoices (53 giọng, có cả Tây Ban Nha/Pháp/Hindi/Ý/Nhật/Bồ
    // Đào Nha/Trung không liên quan tới nội dung bài đọc). Để UI tự quyết
    // định dùng allVoices hay englishVoices, KHÔNG áp đặt cứng ở đây. ────────
    val englishVoices: List<TtsVoiceOption> = allVoices.filter { it.languageTag.startsWith("en") }

    // ── Tra cứu 1 giọng theo sid — dùng khi cần hiển thị TÊN của sid đang
    // chọn (vd TtsVoiceSnapshot.currentSid() trả về Int trần trụi, cần tra
    // ngược ra displayName để hiện lên UI). Trả về null nếu sid không nằm
    // trong danh mục đã biết (hiếm khi xảy ra, trừ khi prefs bị hỏng). ──────
    fun findBySid(sid: Int): TtsVoiceOption? = allVoices.firstOrNull { it.sid == sid }
}