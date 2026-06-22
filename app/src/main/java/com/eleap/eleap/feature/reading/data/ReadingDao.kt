// ReadingDao.kt
package com.eleap.eleap.feature.reading.data

import android.database.sqlite.SQLiteDatabase

class ReadingDao(private val db: SQLiteDatabase) {

    // ── Flow 2: danh sách bài đọc ─────────────────────────────────────────────
    fun getAllReadings(): List<Reading> {
        val list = mutableListOf<Reading>()
        val cursor = db.rawQuery("SELECT * FROM readings ORDER BY reading_id ASC", null)
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    Reading(
                        readingId = it.getInt(it.getColumnIndexOrThrow("reading_id")),
                        titleEn   = it.getString(it.getColumnIndexOrThrow("title_en")),
                        titleVi   = it.getString(it.getColumnIndexOrThrow("title_vi")),
                        level     = it.getString(it.getColumnIndexOrThrow("level")),
                        topic     = it.getString(it.getColumnIndexOrThrow("topic")),
                        createdAt = it.getString(it.getColumnIndexOrThrow("created_at")),
                        updatedAt = it.getString(it.getColumnIndexOrThrow("updated_at")),
                    )
                )
            }
        }
        return list
    }

    // ── Flow 3: load sentences của 1 bài ─────────────────────────────────────
    fun getSentencesByReadingId(readingId: Int): List<ReadingSentence> {
        val list = mutableListOf<ReadingSentence>()
        val cursor = db.rawQuery(
            "SELECT * FROM reading_sentences WHERE reading_id = ? ORDER BY sentence_order ASC",
            arrayOf(readingId.toString())
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    ReadingSentence(
                        sentenceId          = it.getInt(it.getColumnIndexOrThrow("sentence_id")),
                        readingId           = it.getInt(it.getColumnIndexOrThrow("reading_id")),
                        textEn              = it.getString(it.getColumnIndexOrThrow("text_en")),
                        textVi              = it.getString(it.getColumnIndexOrThrow("text_vi")),
                        sentenceExplanation = it.getString(it.getColumnIndexOrThrow("sentence_explanation")),
                        sentenceOrder       = it.getInt(it.getColumnIndexOrThrow("sentence_order")),
                    )
                )
            }
        }
        return list
    }

    // ── Flow 3: load phrases của 1 sentence ──────────────────────────────────
    fun getPhrasesBySentenceId(sentenceId: Int): List<SentencePhrase> {
        val list = mutableListOf<SentencePhrase>()
        val cursor = db.rawQuery(
            "SELECT * FROM sentence_phrases WHERE sentence_id = ?",
            arrayOf(sentenceId.toString())
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    SentencePhrase(
                        phraseId           = it.getInt(it.getColumnIndexOrThrow("phrase_id")),
                        sentenceId         = it.getInt(it.getColumnIndexOrThrow("sentence_id")),
                        textEn             = it.getString(it.getColumnIndexOrThrow("text_en")),
                        textVi             = it.getString(it.getColumnIndexOrThrow("text_vi")),
                        phraseExplanation  = it.getString(it.getColumnIndexOrThrow("phrase_explanation")),
                        startWordOrder     = it.getInt(it.getColumnIndexOrThrow("start_word_order")),
                        endWordOrder       = it.getInt(it.getColumnIndexOrThrow("end_word_order")),
                    )
                )
            }
        }
        return list
    }

    // ── Flow 3: load words của 1 sentence ────────────────────────────────────
    fun getWordsBySentenceId(sentenceId: Int): List<SentenceWord> {
        val list = mutableListOf<SentenceWord>()
        val cursor = db.rawQuery(
            "SELECT * FROM sentence_words WHERE sentence_id = ? ORDER BY word_order ASC",
            arrayOf(sentenceId.toString())
        )
        cursor.use {
            while (it.moveToNext()) {
                val phraseIdIdx = it.getColumnIndexOrThrow("phrase_id")
                list.add(
                    SentenceWord(
                        wordId              = it.getInt(it.getColumnIndexOrThrow("word_id")),
                        sentenceId          = it.getInt(it.getColumnIndexOrThrow("sentence_id")),
                        phraseId            = if (it.isNull(phraseIdIdx)) null else it.getInt(phraseIdIdx),
                        textEn              = it.getString(it.getColumnIndexOrThrow("text_en")),
                        textVi              = it.getString(it.getColumnIndexOrThrow("text_vi")),
                        wordExplanation     = it.getString(it.getColumnIndexOrThrow("word_explanation")),
                        wordOrder           = it.getInt(it.getColumnIndexOrThrow("word_order")),
                        pos                 = it.getString(it.getColumnIndexOrThrow("pos")),
                        lemma               = it.getString(it.getColumnIndexOrThrow("lemma")),
                        wordFormExplanation = it.getString(it.getColumnIndexOrThrow("word_form_explanation")),
                    )
                )
            }
        }
        return list
    }
}