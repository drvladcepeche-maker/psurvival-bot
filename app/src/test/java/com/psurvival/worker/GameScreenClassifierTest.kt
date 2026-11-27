package com.psurvival.worker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GameScreenClassifierTest {
    @Test fun recognizesCityFromObservedRussianBuildingLabels() {
        val result = GameScreenClassifier.classify("Стройка Наука Лагерь Стрелков Транспортный завод Улучшите лагерь")
        assertEquals("city", result.getString("category"))
        assertFalse(result.getBoolean("actionable"))
    }

    @Test fun recognizesEnglishMapLabels() {
        val result = GameScreenClassifier.classify("World Map Gather March")
        assertEquals("map", result.getString("category"))
    }

    @Test fun leavesAmbiguousOrUnrecognizedScreensUnknown() {
        val result = GameScreenClassifier.classify("34 743K VIP10 33,784,157")
        assertEquals("unknown", result.getString("category"))
        assertFalse(result.getBoolean("actionable"))
    }

    @Test fun handlesEmptyOcr() {
        val result = GameScreenClassifier.classify("")
        assertEquals("unknown", result.getString("category"))
        assertEquals("empty_ocr_text", result.getString("reason"))
    }
}
