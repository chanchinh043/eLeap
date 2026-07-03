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
    val phraseId: String?,      // null nếu từ đứng độc lập, không thuộc cụm từ nào;
    // nếu có giá trị thì phải khớp đúng 1 phrase trong sentence này
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
// Khác bản tham khảo (readings.db) ở 1 điểm: MyReading có thêm khái niệm
// "phrase" (cụm từ) — nhưng phrase KHÔNG bắt buộc phủ kín toàn bộ câu. Chỉ
// những cụm từ thực sự có nghĩa/cấu trúc mới được gộp thành phrase (>= 2 từ,
// không chồng lấn); từ nào không thuộc cụm nào thì đứng độc lập (phrase_id
// = null), giống 1 "word" bình thường.

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
          "phrase_id": "<id cụm từ mà từ này thuộc về, ví dụ 'p1'; null nếu từ này đứng độc lập, không thuộc cụm từ nào>",
          "explanation": "<giải thích từ bằng tiếng Việt hoặc null>",
          "form_explanation": "<giải thích dạng từ (ví dụ 'Dạng số nhiều của storm') hoặc null>"
        }
      ]
    }
  ]
}

Quy trình xử lý — PHẢI làm tuần tự theo đúng thứ tự sau, xử lý XONG HẲN 1 câu (đủ 3 bước) rồi mới chuyển sang câu tiếp theo (không nhảy qua lại giữa các câu, không xử lý nhiều câu cùng lúc, không quay lại sửa câu trước):

Với MỖI câu, theo đúng thứ tự:
  Bước 1 — Xử lý câu: dịch text_vi và viết explanation cho câu đó.
  Bước 2 — Xử lý cụm từ (phrases) của câu đó: xác định những cụm từ THỰC SỰ có nghĩa/cấu trúc đáng chú ý (collocation, cụm động từ, cụm giới từ, thành ngữ...) trong câu. Với mỗi cụm, xác định đúng start_word_order/end_word_order — là vị trí của từ đầu tiên/cuối cùng trong cụm, tính theo thứ tự xuất hiện của từ trong câu (tách theo khoảng trắng, bắt đầu đếm từ 1).
  Bước 3 — Xử lý từng từ (words) của câu đó: liệt kê ĐẦY ĐỦ tất cả các từ trong câu theo đúng word_order 1, 2, 3, ... liên tục; với mỗi từ điền text_vi/pos/lemma/explanation/form_explanation, và gán phrase_id bằng cách đối chiếu word_order của từ đó với các phrase đã xác định ở Bước 2 — nếu word_order nằm trong khoảng [start_word_order, end_word_order] của 1 phrase thì gán đúng id của phrase đó, nếu không nằm trong phrase nào thì để phrase_id = null.

Chỉ khi hoàn tất cả 3 bước trên cho câu hiện tại (đủ text_vi, explanation, phrases, words) mới được chuyển sang câu tiếp theo.

Quy tắc bắt buộc:
- Mỗi câu phải có đúng số lượng word bằng số từ trong câu (tách theo khoảng trắng, giữ nguyên dấu câu dính liền), word_order bắt đầu từ 1 và tăng liên tục.
- Chỉ tạo phrase cho những cụm từ THỰC SỰ có nghĩa/cấu trúc đáng chú ý. KHÔNG bắt buộc phrase phải phủ kín toàn bộ câu — những từ không thuộc cụm từ nào thì để đứng độc lập (không gán vào phrase nào cả).
- BẮT BUỘC: mỗi phrase phải có ÍT NHẤT 2 từ (end_word_order - start_word_order >= 1). Không tạo phrase chỉ gồm 1 từ.
- BẮT BUỘC: các phrase trong cùng 1 câu KHÔNG được chồng lấn lên nhau (1 word_order chỉ được thuộc tối đa 1 phrase). Các phrase không cần liên tiếp nhau và không cần bắt đầu từ word_order = 1.
- BẮT BUỘC: start_word_order/end_word_order của mỗi phrase phải khớp CHÍNH XÁC với phrase_id mà chính các word đó được gán ở Bước 3 — đây là điểm hay sai nhất, hãy rà soát lại trước khi trả kết quả.
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
// 5. Repair + Validate
// ─────────────────────────────────────────────────────────────────────────────
//
// AI trả về vị trí phrase theo 2 nguồn riêng biệt: "phrases[].start/end_word_
// order" (AI tự khai) và "words[].phrase_id" (AI tự gán cho từng từ). Trên
// thực tế AI hay bị LỆCH giữa 2 nguồn này (đếm nhầm vị trí dù gán phrase_id
// cho từng từ vẫn đúng), khiến validate cũ từ chối dữ liệu dù về ngữ nghĩa
// hoàn toàn ổn.
//
// Giải pháp: chỉ tin DUY NHẤT 1 nguồn — "words[].phrase_id" — rồi TỰ DỰNG
// LẠI start_word_order/end_word_order của từng phrase bằng cách gom các từ
// liên tiếp có cùng phrase_id. Nhờ vậy start/end luôn nhất quán 100% với
// phrase_id của words theo đúng cấu trúc, không còn khả năng "2 nguồn cãi
// nhau" nữa. phrases[].text_en/text_vi/explanation (nội dung mô tả cụm từ)
// vẫn được AI cung cấp và giữ nguyên, chỉ có vị trí là do ta tính lại.
//
// Trả về data đã sửa (dùng để ghi DB) nếu hợp lệ, hoặc lý do lỗi nếu dữ liệu
// AI sai đến mức không thể tự sửa được (sai số từ, thiếu/trùng word_order,
// hoặc 1 phrase_id bị AI gán cho 2 nhóm từ không liền kề nhau).
//
// wordCounts: map sentence_order -> số từ THẬT của câu đó (lấy từ DB, không
// tin số từ AI tự đếm) để đối chiếu.

data class MyAiRepairResult(
    val data: MyAiReading?,
    val error: String?,
)

fun repairAndValidateMyAiReading(aiData: MyAiReading, wordCounts: Map<Int, Int>): MyAiRepairResult {
    val fixedSentences = mutableListOf<MyAiSentence>()

    for (s in aiData.sentences) {
        val expectedWordCount = wordCounts[s.sentenceOrder]
            ?: return MyAiRepairResult(null, "Câu #${s.sentenceOrder}: không tìm thấy trong DB")

        if (s.words.size != expectedWordCount) {
            return MyAiRepairResult(
                null,
                "Câu #${s.sentenceOrder}: số word AI trả (${s.words.size}) khác số từ thật ($expectedWordCount)"
            )
        }

        // word_order phải đầy đủ, liên tục, không trùng, từ 1..expectedWordCount.
        val sortedWords = s.words.sortedBy { it.wordOrder }
        for ((idx, w) in sortedWords.withIndex()) {
            if (w.wordOrder != idx + 1) {
                return MyAiRepairResult(
                    null,
                    "Câu #${s.sentenceOrder}: word_order không đầy đủ/liên tục 1..$expectedWordCount (lệch tại vị trí ${idx + 1}, gặp word_order=${w.wordOrder})"
                )
            }
        }

        // Gom các từ liên tiếp cùng phrase_id thành 1 phrase, vị trí tính từ
        // chính word_order thật — không dùng start/end AI tự khai.
        val phraseMetaById = s.phrases.associateBy { it.id }
        val fixedPhrases = mutableListOf<MyAiPhrase>()
        val idsUsed = mutableSetOf<String>()
        var i = 0
        while (i < sortedWords.size) {
            val pid = sortedWords[i].phraseId
            if (pid == null) {
                i++
                continue
            }
            var j = i + 1
            while (j < sortedWords.size && sortedWords[j].phraseId == pid) j++
            val groupSize = j - i

            if (!idsUsed.add(pid)) {
                return MyAiRepairResult(
                    null,
                    "Câu #${s.sentenceOrder}: phrase_id '$pid' bị gán cho 2 nhóm từ không liền kề nhau"
                )
            }
            if (groupSize < 2) {
                return MyAiRepairResult(
                    null,
                    "Câu #${s.sentenceOrder}: phrase '$pid' chỉ có $groupSize từ (yêu cầu >= 2)"
                )
            }

            val startOrder = sortedWords[i].wordOrder
            val endOrder = sortedWords[j - 1].wordOrder
            val meta = phraseMetaById[pid]
            fixedPhrases += MyAiPhrase(
                id             = pid,
                textEn         = meta?.textEn ?: sortedWords.subList(i, j).joinToString(" ") { it.textEn },
                textVi         = meta?.textVi,
                explanation    = meta?.explanation,
                startWordOrder = startOrder,
                endWordOrder   = endOrder,
            )
            i = j
        }

        fixedSentences += s.copy(phrases = fixedPhrases)
    }

    return MyAiRepairResult(MyAiReading(aiData.titleVi, aiData.level, aiData.topic, fixedSentences), null)
}