package com.bfyy.dentalime

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PinyinDictionaryTest {
    @Test
    fun dentalTermsAreFirstForFullPinyin() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dictionary = PinyinDictionary(context)
        try {
            assertEquals("牙体牙髓", dictionary.lookup("yatiyasui").first().word)
            assertEquals("根尖周炎", dictionary.lookup("genjianzhouyan").first().word)
            assertEquals("种植牙", dictionary.lookup("zhongzhiya").first().word)
        } finally {
            dictionary.close()
        }
    }

    @Test
    fun generalCandidatesRemainAvailable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val memory = CandidateMemory(context, "general_candidates_test")
        memory.clear()
        val dictionary = PinyinDictionary(context, memory)
        try {
            assertEquals("你好", dictionary.lookup("nihao").first().word)
            assertFalse(dictionary.lookup("niha").isEmpty())
            val zhengji = dictionary.lookup("zhengji").map { it.word }
            assertEquals("征集", zhengji.first())
            assertEquals(true, "正畸" in zhengji.take(5))
        } finally {
            dictionary.close()
            memory.clear()
        }
    }

    @Test
    fun completePinyinExposesTheCharacterIndex() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PinyinDictionary(context, CandidateMemory(context, "character_index_test")).use { dictionary ->
            val yang = dictionary.lookupExact("yang", limit = 200).map { it.word }
            assertTrue(yang.toString(), yang.size >= 100)
            assertTrue(yang.toString(), "杨" in yang)
            assertTrue(yang.toString(), "阳" in yang)
            assertTrue(yang.toString(), "羊" in yang)
            assertTrue(dictionary.lookupExact("mao", limit = 100).any { it.word == "猫" })
        }
    }

    @Test
    fun completePinyinReturnsEveryExactEntryWithoutPrefixMatches() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PinyinDictionary(context, CandidateMemory(context, "all_exact_test")).use { dictionary ->
            assertTrue(dictionary.isCompletePinyin("ya"))
            assertTrue(dictionary.isCompletePinyin("yan"))
            assertTrue(dictionary.isCompletePinyin("yang"))
            assertFalse(dictionary.isCompletePinyin("y"))
            val all = dictionary.lookupAllExact("yang")
            val count = SQLiteDatabase.openDatabase(
                context.noBackupFilesDir.resolve("pinyin.db").path,
                null, SQLiteDatabase.OPEN_READONLY
            ).use { database ->
                database.rawQuery("SELECT count(*) FROM entries WHERE pinyin = ?", arrayOf("yang"))
                    .use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
            }
            assertEquals(count, all.size)
            assertTrue(all.all { it.pinyin == "yang" })
            assertTrue(all.any { it.word == "阳" })
        }
    }

    @Test
    fun selectedWordMovesAheadAndCanBeCleared() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val memory = CandidateMemory(context, "candidate_memory_test")
        memory.clear()
        try {
            PinyinDictionary(context, memory).use { dictionary ->
                assertEquals("征集", dictionary.lookup("zhengji").first().word)
                dictionary.recordSelection("zhengji", "正畸")
                assertEquals("正畸", dictionary.lookup("zhengji").first().word)
                assertEquals("你好", dictionary.lookup("nihao").first().word)
            }
            PinyinDictionary(context, CandidateMemory(context, "candidate_memory_test")).use { dictionary ->
                assertEquals("正畸", dictionary.lookup("zhengji").first().word)
                memory.clear()
                assertEquals("征集", dictionary.lookup("zhengji").first().word)
            }
        } finally {
            memory.clear()
        }
    }

    @Test
    fun rememberedSentenceReturnsEvenWhenOutsideDefaultCandidates() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val memory = CandidateMemory(context, "sentence_recall_test")
        memory.clear()
        try {
            val originalFirst = PinyinDictionary(context, memory).use { dictionary ->
                dictionary.lookup("srds", limit = 2).first().word
            }
            assertTrue(originalFirst != "虽然但是")
            PinyinDictionary(context, memory).use { dictionary ->
                dictionary.recordSelection("srds", "虽然但是")
                assertEquals("虽然但是", dictionary.lookup("srds", limit = 2).first().word)
                assertEquals("虽然但是", dictionary.lookup("srds").first().word)
                assertTrue(dictionary.lookup("srdsx").firstOrNull()?.word != "虽然但是")
            }
            PinyinDictionary(context, CandidateMemory(context, "sentence_recall_test")).use { dictionary ->
                assertEquals("虽然但是", dictionary.lookup("srds").first().word)
                memory.clear()
                assertEquals(originalFirst, dictionary.lookup("srds", limit = 2).first().word)
            }
        } finally {
            memory.clear()
        }
    }

    @Test
    fun continuousPinyinOffersGeneralAndDentalSentences() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PinyinDictionary(context, CandidateMemory(context, "sentence_test")).use { dictionary ->
            assertEquals("我好想你", dictionary.lookup("wohaoxiangni").first().word)
            assertTrue(dictionary.lookup("woyaobaya").take(10).any { it.word == "我要拔牙" })
            assertTrue(
                dictionary.lookup("jintianzuolegenguanzhiliao").take(10)
                    .any { it.word == "今天做了根管治疗" }
            )
            assertTrue(
                dictionary.lookup("wojintianyaobaya").take(10)
                    .any { it.word == "我今天要拔牙" }
            )
            assertTrue(
                dictionary.lookup("mingtianqukouqiangke").take(10)
                    .any { it.word == "明天去口腔科" }
            )
            assertTrue(
                dictionary.lookup("huanzheyayinchuxue").take(10)
                    .any { it.word == "患者牙龈出血" }
            )
            assertTrue(dictionary.lookup("yayinchuxue").any { it.word == "牙龈出血" })
            assertTrue(
                dictionary.lookup("jianchazhongzhitizhouweinianmoyan").take(10)
                    .any { it.word == "检查种植体周围黏膜炎" }
            )
            assertFalse(dictionary.lookup("woyaobaya".repeat(6)).isEmpty())
            assertEquals("你好", dictionary.lookup("nihao").first().word)
        }
    }

    @Test
    fun fullSentenceSelectionIsRemembered() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val memory = CandidateMemory(context, "sentence_memory_test")
        memory.clear()
        try {
            PinyinDictionary(context, memory).use { dictionary ->
                assertEquals("我好想你", dictionary.lookup("wohaoxiangni").first().word)
                dictionary.recordSelection("wohaoxiangni", "我好像你")
                assertEquals("我好像你", dictionary.lookup("wohaoxiangni").first().word)
            }
        } finally {
            memory.clear()
        }
    }

    @Test
    fun sentenceSegmentsCanBeCorrectedWithoutChangingOtherWords() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PinyinDictionary(context, CandidateMemory(context, "segment_test")).use { dictionary ->
            val input = "wohaoxiangni"
            val sentence = dictionary.lookup(input).first()
            assertEquals("我好想你", sentence.word)
            assertTrue(sentence.segments.size > 1)
            assertEquals(sentence.word, sentence.segments.joinToString("") { it.word })
            assertEquals(0, sentence.segments.first().start)
            assertEquals(input.length, sentence.segments.last().end)
            sentence.segments.zipWithNext().forEach { (left, right) ->
                assertEquals(left.end, right.start)
            }
            val index = sentence.segments.indexOfFirst { it.word.contains("想") }
            assertTrue(index >= 0)
            val segment = sentence.segments[index]
            val segmentPinyin = input.substring(segment.start, segment.end)
            val alternative = dictionary.lookupExact(segmentPinyin)
                .firstOrNull { it.word != segment.word }
            assertTrue(alternative != null)
            val corrected = sentence.replaceSegment(index, alternative!!.word)
            assertEquals(input, corrected.pinyin)
            assertEquals(sentence.segments.take(index), corrected.segments.take(index))
            assertEquals(sentence.segments.drop(index + 1), corrected.segments.drop(index + 1))
            assertEquals(alternative.word, corrected.segments[index].word)
            assertEquals(corrected.segments.joinToString("") { it.word }, corrected.word)
        }
    }

    @Test
    fun exactSegmentChoicesIncludeDentalAlternative() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val memory = CandidateMemory(context, "segment_choice_test")
        memory.clear()
        try {
            PinyinDictionary(context, memory).use { dictionary ->
                val choices = dictionary.lookupExact("zhengji")
                assertTrue(choices.any { it.word == "正畸" })
                assertTrue(choices.all { it.pinyin == "zhengji" })
                dictionary.recordSelection("zhengji", "正畸")
                assertEquals("正畸", dictionary.lookupExact("zhengji").first().word)
            }
        } finally {
            memory.clear()
        }
    }

    @Test
    fun initialsOfferMultipleSentenceChoicesAndRememberSelection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val memory = CandidateMemory(context, "initials_test")
        memory.clear()
        try {
            PinyinDictionary(context, memory).use { dictionary ->
                val choices = dictionary.lookup("whxn")
                val words = choices.map { it.word }
                assertTrue(words.toString(), "我好想你" in words)
                assertTrue(words.toString(), "我很想你" in words)
                assertTrue(words.toString(), "我会想你" in words)
                assertTrue(dictionary.lookup("wxy").any { it.word == "我想要" })
                val otherSentence = dictionary.lookup("wxn").map { it.word }
                assertTrue(otherSentence.toString(), "我想你" in otherSentence)
                assertTrue(dictionary.lookup("gjzy").any { it.word == "根尖周炎" })
                assertTrue(dictionary.lookup("ytys").any { it.word == "牙体牙髓" })
                assertTrue(choices.filter { it.segments.isNotEmpty() }.all { candidate ->
                    candidate.segments.first().start == 0 &&
                        candidate.segments.last().end == 4 &&
                        candidate.segments.joinToString("") { it.word } == candidate.word
                })
                dictionary.recordSelection("whxn", "我很想你")
                assertEquals("我很想你", dictionary.lookup("whxn").first().word)
                assertEquals("我好想你", dictionary.lookup("wohaoxiangni").first().word)
            }
        } finally {
            memory.clear()
        }
    }

    @Test
    fun mixedPinyinComposesWordsAndSupportsSegmentCorrection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val memory = CandidateMemory(context, "mixed_test")
        memory.clear()
        try {
            PinyinDictionary(context, memory).use { dictionary ->
                val input = "woxihn"
                SQLiteDatabase.openDatabase(
                    context.noBackupFilesDir.resolve("pinyin.db").path,
                    null, SQLiteDatabase.OPEN_READONLY
                ).use { database ->
                    val options = MixedPinyinMatcher(database).options(input)
                    assertTrue(options["woxih"].orEmpty().toString(),
                        options["woxih"].orEmpty().any { it.word == "我喜欢" })
                    assertTrue(options["n"].orEmpty().toString(),
                        options["n"].orEmpty().any { it.word == "你" })
                }
                SQLiteDatabase.openDatabase(
                    context.noBackupFilesDir.resolve("pinyin.db").path,
                    null, SQLiteDatabase.OPEN_READONLY
                ).use { database ->
                    val options = MixedPinyinMatcher(database).options("wjintianyaobaya")
                    assertTrue(options["wjintian"].orEmpty().toString(),
                        options["wjintian"].orEmpty().any { it.word == "我今天" })
                    assertTrue(options["baya"].orEmpty().toString(),
                        options["baya"].orEmpty().any { it.word == "拔牙" })
                }
                val choices = dictionary.lookup(input)
                val sentence = choices.firstOrNull { it.word == "我喜欢你" }
                assertTrue(choices.map { it.word }.toString(), sentence != null)
                assertTrue(choices.map { it.word }.toString(),
                    choices.indexOfFirst { it.word == "我喜欢你" } < 3)
                assertEquals(0, sentence!!.segments.first().start)
                assertEquals(input.length, sentence.segments.last().end)
                assertEquals(sentence.word, sentence.segments.joinToString("") { it.word })
                sentence.segments.zipWithNext().forEach { (left, right) ->
                    assertEquals(left.end, right.start)
                }
                assertTrue(dictionary.lookupExact("xih").any { it.word == "喜欢" })
                assertTrue(dictionary.lookup("wxihn").any { it.word == "我喜欢你" })
                assertTrue(dictionary.lookup("genjzhouyan").any { it.word == "根尖周炎" })
                val longChoices = dictionary.lookup("wjintianyaobaya")
                assertTrue(longChoices.map { it.word }.toString(),
                    longChoices.any { it.word == "我今天要拔牙" })
                dictionary.recordSelection(input, "我喜欢你")
                assertEquals("我喜欢你", dictionary.lookup(input).first().word)
                assertEquals("我好想你", dictionary.lookup("wohaoxiangni").first().word)
                assertTrue(dictionary.lookup("whxn").any { it.word == "我很想你" })
            }
        } finally {
            memory.clear()
        }
    }

    @Test
    fun initialsCanBeSelectedAsProgressivePrefixes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PinyinDictionary(context, CandidateMemory(context, "prefix_test")).use { dictionary ->
            val sun = dictionary.lookupPrefixes("tym").firstOrNull { it.candidate.word == "太阳" }
            assertTrue("tym prefixes", sun != null)
            assertEquals(2, sun!!.consumed)
            val although = dictionary.lookupPrefixes("srdr").firstOrNull { it.candidate.word == "虽然" }
            assertTrue("exact=" + dictionary.lookupExact("sr").toString() +
                " prefixes=" + dictionary.lookupPrefixes("srdr"), although != null)
            assertEquals(2, although!!.consumed)
        }
    }

    @Test
    fun singleInitialOffersCharacterCandidates() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PinyinDictionary(context, CandidateMemory(context, "single_initial_test")).use { dictionary ->
            val choices = dictionary.lookup("m", limit = 30)
            assertTrue(choices.map { it.word }.toString(), choices.size >= 20)
            val yChoices = dictionary.lookup("y", limit = 28)
            assertTrue(yChoices.toString(), yChoices.isNotEmpty())
            assertTrue(dictionary.lookup("mao").any { it.word == "猫" })
        }
    }
}
