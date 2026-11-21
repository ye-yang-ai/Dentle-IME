package com.bfyy.dentalime

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
}
