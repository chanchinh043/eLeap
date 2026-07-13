// TtsKokoroVoices.kt
// Đặt tại: com/eleap/eleap/core/tts/kokoro/TtsKokoroVoices.kt
//
// Danh mục giọng đọc RIÊNG của nhà cung cấp Kokoro — chuyển từ
// TtsVoiceCatalog.kt cũ sang đây khi tách theo từng nhà cung cấp. Nhà cung
// cấp khác (google_cloud/...) sẽ có 1 file tương tự trong thư mục riêng của
// nó, KHÔNG đụng gì tới file này.
//
// ⚠️ sid ở đây chỉ có Ý NGHĨA NỘI BỘ trong phạm vi Kokoro — KHÔNG cần né số
// với vendor khác nữa (xem TtsAudioCache.kt: path cache đã có `vendor` để
// tách namespace, và TtsVoiceSnapshot lưu cặp (vendor, sid) chứ không phải
// mỗi sid). sid PHẢI khớp CHÍNH XÁC với sid mà server Kokoro dùng để đặt
// tên file .zip trên Drive (xem TtsGoogleDriveSource.kt).
//
// Nguồn danh sách 53 giọng Kokoro (kokoro-multi-lang-v1_0, sid 0-52):
// https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/kokoro.html
// Quy ước tên: ký tự đầu = ngôn ngữ (a=Anh-Mỹ, b=Anh-Anh, e=Tây Ban Nha,
// f=Pháp, h=Hindi, i=Ý, j=Nhật, p=Bồ Đào Nha/Brazil, z=Trung), ký tự thứ 2 =
// giới tính (f=nữ, m=nam).
package com.eleap.eleap.core.tts.kokoro

import com.eleap.eleap.core.tts.TtsVendor
import com.eleap.eleap.core.tts.TtsVoiceOption

object TtsKokoroVoices {

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
            vendor = TtsVendor.KOKORO,
            sid = sid,
            voiceName = voiceName,
            languageTag = languageTag,
            displayName = displayName,
        )
    }

    // ── Toàn bộ 53 giọng Kokoro (kokoro-multi-lang-v1_0), sid 0-52 — ĐÚNG
    // khớp với sid server dùng để build gói .zip trên Drive, KHÔNG được tự ý
    // đổi số ở đây nếu không đổi tương ứng ở pipeline server. ────────────────
    val voices: List<TtsVoiceOption> = listOf(
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
}