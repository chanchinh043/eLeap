// TtsMyReadingContentHash.kt
// Đặt tại: com/eleap/eleap/core/tts/kokoro/myreading/TtsMyReadingContentHash.kt
//
// Tính "contentHash" TỔNG HỢP cho CẢ 1 bài MyReading — khác hẳn
// TtsAudioCache.contentHash() (hash của ĐÚNG 1 text word/phrase/sentence lẻ,
// dùng để tra cache 1 FILE cụ thể). Hash ở ĐÂY dùng làm 1 phần khoá dedup
// khi GỬI YÊU CẦU xử lý cho server (xem TtsMyReadingRequestClient.kt) —
// mục đích là để server tự nhận ra "bài này, tại thời điểm này, nội dung y
// hệt lần trước đã yêu cầu" hay "nội dung đã đổi, cần job mới".
//
// ⚠️ VÌ SAO CẦN HASH RIÊNG Ở CẤP BÀI (không tái dùng contentHash từng câu):
// job xử lý phía server là CHO CẢ BÀI (server tổng hợp giọng cho MỌI câu/
// cụm/từ trong 1 lượt, đóng gói vào 1 file .zip theo (readingId, sid) — xem
// ghi chú TtsGoogleDriveSource.kt về quy ước tên file "{readingId}_{sid}.zip").
// Nếu chỉ 1 câu trong bài bị sửa, cả gói .zip phải build lại — nên khoá dedup
// PHẢI phản ánh đúng toàn bộ nội dung bài, không phải từng câu lẻ.
//
// ⚠️ NGUỒN DỮ LIỆU: dùng list ReadingSentence đã có sẵn (từ
// MyReadingRepository.getReading()/ReadingRepository.getReading()) — CHÍNH
// LÀ dữ liệu cuối cùng dùng để hiển thị UI và để TtsPlaybackRouter tính
// contentHash từng item khi phát. Dùng lại đúng nguồn này đảm bảo hash tổng
// hợp LUÔN khớp với nội dung thật sự sẽ được đọc, không có nguy cơ lệch pha
// với dữ liệu đã sync lên Supabase.
//
// ⚠️ CHỈ GHÉP textEn CỦA SENTENCES (không ghép riêng phrases/words): mọi
// text của phrase/word đều là SUBSTRING nằm trong textEn của sentence chứa
// nó (xem cấu trúc SentencePhrase/SentenceWord ở ReadingRepository.kt — định
// vị bằng start_word_order/end_word_order/word_order trên CHÍNH câu cha) —
// nên nội dung sentences ĐÃ ĐỦ để xác định toàn bộ text server cần tổng hợp.
// Ghép thêm phrases/words vào hash sẽ dư thừa, không tăng độ chính xác.
package com.eleap.eleap.core.tts.kokoro.myreading

import com.eleap.eleap.feature.reading.data.ReadingSentence
import java.security.MessageDigest

object TtsMyReadingContentHash {

    // Dài hơn TtsAudioCache.contentHash() (8 ký tự) vì đây là khoá dedup cho
    // CẢ BÀI (nhiều câu ghép lại) — không gian giá trị đầu vào lớn hơn hẳn 1
    // text lẻ, lấy dài hơn để giảm rủi ro va chạm giữa 2 bài nội dung khác
    // nhau vô tình ra cùng hash ngắn. Vẫn KHÔNG cần cryptographic mạnh, chỉ
    // để SO SÁNH THAY ĐỔI NỘI DUNG — cùng tinh thần TtsAudioCache.contentHash().
    private const val HASH_LENGTH = 16

    // Dấu phân cách giữa các câu khi ghép — KHÔNG dùng ký tự có thể xuất
    // hiện tự nhiên trong text_en (vd khoảng trắng, dấu câu thường) để tránh
    // 2 cách chia câu khác nhau vô tình ghép ra cùng 1 chuỗi (vd câu A="ab"
    // + câu B="c" ghép "ab"+"c" = "abc", trùng với 1 câu duy nhất "abc" nếu
    // không có gì phân tách).
    private const val SEPARATOR = "\u241F" // Unicode "Symbol for Unit Separator" — không xuất hiện trong text thường

    // ── Điểm gọi CHÍNH — tính hash tổng hợp từ danh sách sentence của 1 bài.
    // PHẢI sắp xếp theo sentenceOrder trước khi ghép — đảm bảo hash ổn định
    // bất kể thứ tự trả về từ DB/list truyền vào (SQLite không đảm bảo thứ
    // tự nếu caller quên ORDER BY ở đâu đó), tránh 2 lần gọi liên tiếp cho
    // CÙNG nội dung lại ra 2 hash khác nhau chỉ vì thứ tự list khác nhau. ───
    fun compute(sentences: List<ReadingSentence>): String {
        val joined = sentences
            .sortedBy { it.sentenceOrder }
            .joinToString(SEPARATOR) { it.textEn.orEmpty() }

        val digest = MessageDigest.getInstance("SHA-256").digest(joined.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(HASH_LENGTH)
    }
}