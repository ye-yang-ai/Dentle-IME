package com.bfyy.dentalime

import org.junit.Assert.assertEquals
import org.junit.Test

class T9Test {
    @Test
    fun encodesPinyinAndDentalTerms() {
        assertEquals("64426", T9.encode("nihao"))
        assertEquals("928492784", T9.encode("yatiyasui"))
        assertEquals("wxyz", T9.letters('9'))
    }
}
