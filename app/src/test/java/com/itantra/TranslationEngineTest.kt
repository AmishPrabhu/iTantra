package com.itantra

import android.content.ContextWrapper
import com.itantra.ai.model.Language
import com.itantra.ai.translation.IndicTranslationEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TranslationEngineTest {

    private lateinit var engine: IndicTranslationEngine

    @Before
    fun setUp() {
        val dummyContext = ContextWrapper(null)
        engine = IndicTranslationEngine(dummyContext)
    }

    @Test
    fun testBachaoHindiToTamil() = runBlocking {
        val result = engine.translateText("मुझे बचाओ मुझे बचाओ", Language.HINDI, Language.TAMIL)
        assertNotNull(result)
        assertTrue("Expected Tamil SOS phrase, got: $result", result.contains("காப்பாற்றுங்கள்"))
    }

    @Test
    fun testBachaoHindiToEnglish() = runBlocking {
        val result = engine.translateText("मुझे बचाओ", Language.HINDI, Language.ENGLISH)
        assertNotNull(result)
        assertTrue("Expected English SOS phrase, got: $result", result.contains("Save me"))
    }

    @Test
    fun testBachaoHindiToMarathi() = runBlocking {
        val result = engine.translateText("बचाओ बचाओ", Language.HINDI, Language.MARATHI)
        assertNotNull(result)
        assertTrue("Expected Marathi SOS phrase, got: $result", result.contains("वाचवा"))
    }

    @Test
    fun testBachaoHindiToTelugu() = runBlocking {
        val result = engine.translateText("bachao bachao", Language.HINDI, Language.TELUGU)
        assertNotNull(result)
        assertTrue("Expected Telugu SOS phrase, got: $result", result.contains("రక్షించండి"))
    }

    @Test
    fun testTrappedDebrisTranslation() = runBlocking {
        val result = engine.translateText("लोग मलबे में फंसे हैं", Language.HINDI, Language.ENGLISH)
        assertNotNull(result)
        assertTrue("Expected English trapped phrase, got: $result", result.contains("debris") || result.contains("trapped"))
    }

    @Test
    fun testInjuredMedicalTranslation() = runBlocking {
        val result = engine.translateText("गंभीर चोट लगी है", Language.HINDI, Language.TAMIL)
        assertNotNull(result)
        assertTrue("Expected Tamil injury phrase, got: $result", result.contains("காயம்"))
    }

    @Test
    fun testHelloBrotherTranslation() = runBlocking {
        val result = engine.translateText("हैलो भाई सुनो", Language.HINDI, Language.TAMIL)
        assertNotNull(result)
        assertTrue("Expected Tamil greeting phrase, got: $result", result.contains("வணக்கம்"))
    }
}
