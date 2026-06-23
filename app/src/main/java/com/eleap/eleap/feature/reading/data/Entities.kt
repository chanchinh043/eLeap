// Entities.kt
package com.eleap.eleap.feature.reading.data

// ── readings ──────────────────────────────────────────────────────────────────
data class Reading(
    val readingId: Int,
    val titleEn: String?,
    val titleVi: String?,
    val level: String?,
    val topic: String?,
    val createdAt: String?,
    val updatedAt: String?,
)

// ── reading_sentences ─────────────────────────────────────────────────────────
data class ReadingSentence(
    val sentenceId: Int,
    val readingId: Int,
    val textEn: String?,
    val textVi: String?,
    val sentenceExplanation: String?,
    val sentenceOrder: Int,
    val phrases: List<SentencePhrase> = emptyList(),
    val words: List<SentenceWord>   = emptyList(),
)

// ── sentence_phrases ──────────────────────────────────────────────────────────
data class SentencePhrase(
    val phraseId: Int,
    val sentenceId: Int,
    val textEn: String?,
    val textVi: String?,
    val phraseExplanation: String?,
    val startWordOrder: Int,
    val endWordOrder: Int,
)

// ── sentence_words ────────────────────────────────────────────────────────────
data class SentenceWord(
    val wordId: Int,
    val sentenceId: Int,
    val phraseId: Int?,
    val textEn: String?,
    val textVi: String?,
    val wordExplanation: String?,
    val wordOrder: Int,
    val pos: String?,
    val lemma: String?,
    val wordFormExplanation: String?,
)

// ── dict (dict.db) ────────────────────────────────────────────────────────────
data class DictEntry(
    val word: String,
    val meaning: String?,
    val shortMeaning: String?,
)