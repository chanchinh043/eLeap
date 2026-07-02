// MyReadingAiApiClient.kt
// Đặt tại: feature/myreading/data/MyReadingAiApiClient.kt
//
// Cùng package với MyReadingRepository.kt (com.eleap.eleap.feature.myreading.data)
// để writeAiResult() dùng thẳng MyAiReading mà không cần import.
package com.eleap.eleap.feature.myreading.data

import android.util.Log
import com.eleap.eleap.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val TAG          = "MyReadingAiApiClient"
private const val OPENAI_MODEL = "gpt-4.1-mini"
private const val OPENAI_URL   = "https://api.openai.com/v1/chat/completions"
private const val MAX_TOKENS   = 16000

// Logcat cắt bớt mỗi dòng log dài (~4000 ký tự) — chia nhỏ để log đầy đủ.
private const val LOG_CHUNK_SIZE = 3500

private fun logLong(tag: String, label: String, content: String) {
    if (content.isEmpty()) {
        Log.d(tag, "$label: (rỗng)")
        return
    }
    if (content.length <= LOG_CHUNK_SIZE) {
        Log.d(tag, "$label (${content.length} ký tự):\n$content")
        return
    }
    val chunks = content.chunked(LOG_CHUNK_SIZE)
    Log.d(tag, "$label (${content.length} ký tự, chia ${chunks.size} phần để log) ↓↓↓")
    chunks.forEachIndexed { i, chunk ->
        Log.d(tag, "$label [${i + 1}/${chunks.size}]:\n$chunk")
    }
    Log.d(tag, "$label ↑↑↑ (hết)")
}

// ─────────────────────────────────────────────────────────────────────────────
// 1. Data classes — kết quả parse từ JSON AI trả về
// ─────────────────────────────────────────────────────────────────────────────

data class MyAiWord(
    val wordOrder: Int,
    val textEn: String,
    val textVi: String?,
    val pos: String?,
    val lemma: String?,
    val phraseId: String?,      // luôn khớp 1 phrase trong sentence này (rule: phrase phủ hết câu)
    val explanation: String?,
    val formExplanation: String?,
)

data class MyAiPhrase(
    val id: String,
    val textEn: String,
    val textVi: String?,
    val explanation: String?,
    val startWordOrder: Int,
    val endWordOrder: Int,      // rule: endWordOrder - startWordOrder + 1 >= 2
)

data class MyAiSentence(
    val sentenceOrder: Int,
    val textEn: String,
    val textVi: String?,
    val explanation: String?,
    val phrases: List<MyAiPhrase>,
    val words: List<MyAiWord>,
)

data class MyAiReading(
    val titleVi: String?,
    val level: String?,
    val topic: String?,
    val sentences: List<MyAiSentence>,
)

// ─────────────────────────────────────────────────────────────────────────────
// 2. Prompt builder
// ─────────────────────────────────────────────────────────────────────────────
//
// Khác bản tham khảo (readings.db) ở 1 điểm quan trọng: MyReading BẮT BUỘC
// phrase phủ kín 100% số từ trong câu (không có từ nào nằm ngoài phrase), và
// mỗi phrase phải có ít nhất 2 từ. Nói cách khác: các phrase của 1 câu chia
// câu thành các cụm liên tiếp, không chồng lấn, không có khoảng trống, từ
// word_order = 1 đến hết.

fun buildMyReadingPrompt(titleEn: String, sentences: List<Pair<Int, String>>): String {
    val sentenceBlock = sentences.joinToString("\n") { (order, text) -> "$order. $text" }

    return """
Bạn là trợ lý ngôn ngữ học tiếng Anh cho người học Việt Nam.
Dưới đây là một bài đọc tiếng Anh với tiêu đề và danh sách câu (kèm số thứ tự).

Tiêu đề: $titleEn

Các câu:
$sentenceBlock

Hãy phân tích toàn bộ bài và trả về MỘT object JSON DUY NHẤT (không có markdown, không có backtick, chỉ JSON thuần) với cấu trúc sau:

{
  "title_vi": "...",
  "level": "A1|A2|B1|B2|C1|C2",
  "topic": "chủ đề ngắn gọn bằng tiếng Anh (ví dụ: Travel, Environment, Technology)",
  "sentences": [
    {
      "sentence_order": <số thứ tự câu, đúng như đầu vào>,
      "text_en": "<câu tiếng Anh gốc, giữ nguyên>",
      "text_vi": "<dịch tiếng Việt>",
      "explanation": "<giải thích ngắn bằng tiếng Việt về cấu trúc hoặc ý nghĩa của câu, hoặc null>",
      "phrases": [
        {
          "id": "p1",
          "text_en": "cụm từ tiếng Anh",
          "text_vi": "nghĩa tiếng Việt",
          "explanation": "giải thích cụm từ bằng tiếng Việt",
          "start_word_order": <word_order của từ đầu tiên trong cụm, bắt đầu từ 1>,
          "end_word_order": <word_order của từ cuối cùng trong cụm>
        }
      ],
      "words": [
        {
          "word_order": <1, 2, 3, ... đúng theo thứ tự xuất hiện trong câu>,
          "text_en": "<token tiếng Anh nguyên bản, kể cả dấu câu dính liền>",
          "text_vi": "<nghĩa tiếng Việt của từ>",
          "pos": "<noun|verb|adjective|adverb|preposition|conjunction|determiner|pronoun|interjection|other>",
          "lemma": "<dạng gốc của từ>",
          "phrase_id": "<id cụm từ mà từ này thuộc về, ví dụ 'p1'>",
          "explanation": "<giải thích từ bằng tiếng Việt hoặc null>",
          "form_explanation": "<giải thích dạng từ (ví dụ 'Dạng số nhiều của storm') hoặc null>"
        }
      ]
    }
  ]
}

Quy tắc bắt buộc:
- Mỗi câu phải có đúng số lượng word bằng số từ trong câu (tách theo khoảng trắng, giữ nguyên dấu câu dính liền), word_order bắt đầu từ 1 và tăng liên tục.
- BẮT BUỘC: các phrase của 1 câu phải phủ kín TOÀN BỘ từ trong câu đó — mọi word_order từ 1 đến hết đều phải thuộc đúng 1 phrase, không có từ nào nằm ngoài phrase.
- BẮT BUỘC: các phrase phải liên tiếp nhau và không chồng lấn — phrase đầu tiên bắt đầu từ word_order = 1, phrase cuối cùng kết thúc ở word_order cuối cùng của câu, phrase sau nối liền ngay sau phrase trước (start_word_order của phrase sau = end_word_order của phrase trước + 1).
- BẮT BUỘC: mỗi phrase phải có ÍT NHẤT 2 từ (end_word_order - start_word_order >= 1). Không tạo phrase chỉ gồm 1 từ.
- phrase_id trong words phải khớp chính xác với id trong mảng phrases của câu đó — mọi word đều phải có phrase_id (không được null).
- Không thêm bất kỳ văn bản nào ngoài JSON.
""".trimIndent()
}

// ─────────────────────────────────────────────────────────────────────────────
// 3. Gọi OpenAI API — trả về nội dung text thô của message
// ─────────────────────────────────────────────────────────────────────────────

suspend fun callMyReadingOpenAI(prompt: String, logLabel: String = ""): String = withContext(Dispatchers.IO) {
    val tagLabel = if (logLabel.isBlank()) "" else " [$logLabel]"

    val requestBody = JSONObject().apply {
        put("model", OPENAI_MODEL)
        put("max_tokens", MAX_TOKENS)
        put("messages", JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })
        })
    }.toString()

    Log.d(TAG, "═══ GỬI REQUEST đến OpenAI$tagLabel | model=$OPENAI_MODEL ═══")
    logLong(TAG, "PROMPT gửi đi$tagLabel", prompt)

    val url  = URL(OPENAI_URL)
    val conn = url.openConnection() as HttpURLConnection
    conn.requestMethod = "POST"
    conn.setRequestProperty("Content-Type", "application/json")
    conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.OPENAI_API_KEY}")
    conn.doOutput       = true
    conn.connectTimeout = 30_000
    conn.readTimeout    = 120_000

    val startedAt = System.currentTimeMillis()

    val responseCode: Int
    val response: String
    try {
        conn.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }
        responseCode = conn.responseCode
        val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
        response = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    } catch (e: Exception) {
        val elapsed = System.currentTimeMillis() - startedAt
        Log.e(TAG, "═══ LỖI KẾT NỐI khi gọi OpenAI$tagLabel sau ${elapsed}ms: ${e.javaClass.simpleName}: ${e.message} ═══", e)
        throw e
    } finally {
        conn.disconnect()
    }

    val elapsed = System.currentTimeMillis() - startedAt
    Log.d(TAG, "═══ NHẬN RESPONSE từ OpenAI$tagLabel | HTTP $responseCode | ${elapsed}ms ═══")
    logLong(TAG, "RESPONSE thô (raw body)$tagLabel", response)

    if (responseCode !in 200..299) {
        Log.e(TAG, "═══ OpenAI trả lỗi HTTP $responseCode$tagLabel ═══")
        throw RuntimeException("OpenAI HTTP $responseCode: $response")
    }

    val content = JSONObject(response)
        .getJSONArray("choices")
        .getJSONObject(0)
        .getJSONObject("message")
        .getString("content")
        .trim()

    logLong(TAG, "NỘI DUNG AI TRẢ VỀ (message.content)$tagLabel", content)
    content
}

// ─────────────────────────────────────────────────────────────────────────────
// 4. Parse JSON trả về của AI → MyAiReading
// ─────────────────────────────────────────────────────────────────────────────

fun parseMyReadingAiResponse(raw: String): MyAiReading = try {
    parseMyReadingAiResponseInternal(raw)
} catch (e: Exception) {
    Log.e(TAG, "═══ LỖI PARSE JSON từ AI: ${e.javaClass.simpleName}: ${e.message} ═══", e)
    logLong(TAG, "RAW gây lỗi parse", raw)
    throw e
}

private fun parseMyReadingAiResponseInternal(raw: String): MyAiReading {
    val cleaned = raw
        .removePrefix("```json").removePrefix("```")
        .removeSuffix("```")
        .trim()

    val root = JSONObject(cleaned)

    fun JSONObject.strOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).ifBlank { null }

    val titleVi = root.strOrNull("title_vi")
    val level   = root.strOrNull("level")
    val topic   = root.strOrNull("topic")

    val sentencesArr = root.getJSONArray("sentences")
    val sentences = (0 until sentencesArr.length()).map { si ->
        val s = sentencesArr.getJSONObject(si)

        val phrasesArr = s.optJSONArray("phrases") ?: JSONArray()
        val phrases = (0 until phrasesArr.length()).map { pi ->
            val p = phrasesArr.getJSONObject(pi)
            MyAiPhrase(
                id             = p.getString("id"),
                textEn         = p.getString("text_en"),
                textVi         = p.strOrNull("text_vi"),
                explanation    = p.strOrNull("explanation"),
                startWordOrder = p.getInt("start_word_order"),
                endWordOrder   = p.getInt("end_word_order"),
            )
        }

        val wordsArr = s.optJSONArray("words") ?: JSONArray()
        val words = (0 until wordsArr.length()).map { wi ->
            val w = wordsArr.getJSONObject(wi)
            MyAiWord(
                wordOrder       = w.getInt("word_order"),
                textEn          = w.getString("text_en"),
                textVi          = w.strOrNull("text_vi"),
                pos             = w.strOrNull("pos"),
                lemma           = w.strOrNull("lemma"),
                phraseId        = w.strOrNull("phrase_id"),
                explanation     = w.strOrNull("explanation"),
                formExplanation = w.strOrNull("form_explanation"),
            )
        }

        MyAiSentence(
            sentenceOrder = s.getInt("sentence_order"),
            textEn        = s.getString("text_en"),
            textVi        = s.strOrNull("text_vi"),
            explanation   = s.strOrNull("explanation"),
            phrases       = phrases,
            words         = words,
        )
    }

    Log.d(TAG, "parseMyReadingAiResponse: ${sentences.size} sentence(s), titleVi=$titleVi, level=$level")
    return MyAiReading(titleVi, level, topic, sentences)
}

// ─────────────────────────────────────────────────────────────────────────────
// 5. Validate — kiểm tra rule "phrase phủ kín câu, mỗi phrase >= 2 từ" trước
//    khi ghi DB. Trả về null nếu hợp lệ, hoặc lý do lỗi (String) nếu không.
// ─────────────────────────────────────────────────────────────────────────────
//
// wordCounts: map sentence_order -> số từ THẬT của câu đó (lấy từ DB, không
// tin số từ AI tự đếm) để đối chiếu.

fun validateMyAiReading(aiData: MyAiReading, wordCounts: Map<Int, Int>): String? {
    for (s in aiData.sentences) {
        val expectedWordCount = wordCounts[s.sentenceOrder]
            ?: return "Câu #${s.sentenceOrder}: không tìm thấy trong DB"

        if (s.words.size != expectedWordCount) {
            return "Câu #${s.sentenceOrder}: số word AI trả (${s.words.size}) khác số từ thật ($expectedWordCount)"
        }

        val sortedPhrases = s.phrases.sortedBy { it.startWordOrder }
        var expectedStart = 1
        for (p in sortedPhrases) {
            if (p.startWordOrder != expectedStart) {
                return "Câu #${s.sentenceOrder}: phrase '${p.id}' bắt đầu tại ${p.startWordOrder}, kỳ vọng $expectedStart (phrase phải liên tiếp, không khoảng trống/chồng lấn)"
            }
            if (p.endWordOrder - p.startWordOrder + 1 < 2) {
                return "Câu #${s.sentenceOrder}: phrase '${p.id}' chỉ có ${p.endWordOrder - p.startWordOrder + 1} từ (yêu cầu >= 2)"
            }
            expectedStart = p.endWordOrder + 1
        }
        if (expectedStart - 1 != expectedWordCount) {
            return "Câu #${s.sentenceOrder}: phrase phủ tới word_order ${expectedStart - 1}, chưa phủ hết $expectedWordCount từ"
        }

        val phraseIds = sortedPhrases.map { it.id }.toSet()
        val missingPhraseId = s.words.firstOrNull { it.phraseId == null || it.phraseId !in phraseIds }
        if (missingPhraseId != null) {
            return "Câu #${s.sentenceOrder}: word #${missingPhraseId.wordOrder} có phrase_id không hợp lệ (${missingPhraseId.phraseId})"
        }
    }
    return null
}