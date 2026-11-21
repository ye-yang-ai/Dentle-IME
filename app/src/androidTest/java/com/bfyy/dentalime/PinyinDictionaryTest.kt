package com.bfyy.dentalime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val dictionary = PinyinDictionary(context)
        try {
            assertEquals("你好", dictionary.lookup("nihao").first().word)
            assertFalse(dictionary.lookup("niha").isEmpty())
            val zhengji = dictionary.lookup("zhengji").map { it.word }
            assertEquals("征集", zhengji.first())
            assertEquals(true, "正畸" in zhengji.take(5))
        } finally {
            dictionary.close()
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
}
