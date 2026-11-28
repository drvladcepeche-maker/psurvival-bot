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

    @Test fun recognizesCityFromNoisyRussianTesseractOutput() {
        val result = GameScreenClassifier.classify(
            "743к 1.90м 700к 491к Сторожевая Башня Стройка " +
                "Штаб-Квартира Лагерь Стрелков Лагерь Наездников Лагерь Бойцов " +
                "Транспортный Завод Улучшите Лагерь Стрелков до 16-го Ур."
        )
        assertEquals("city", result.getString("category"))
        assertFalse(result.getBoolean("actionable"))
    }

    @Test fun recognizesCityFromActualMlKitOcrGlyphConfusions() {
        val result = GameScreenClassifier.classify(
            "CTpoMKa (0/1) Hayka (0/1) Jlarepb 5oňLLOB " +
                "Jlarepb CTpenKOB TpaHcnopTHbIY 3aBoA ynyywTe Narepb UTab-KBapTupa"
        )
        assertEquals("city", result.getString("category"))
        assertFalse(result.getBoolean("actionable"))
    }

    @Test fun recognizesCityFromNoisyMultiBlockTesseractCapture() {
        val result = GameScreenClassifier.classify(
            "743к 1.90м 700к 491к 36/135 Стройка Наука " +
                "Сторожевая Башня Лагерь Стрелков Лагерь Наездников Лагерь Бойцов " +
                "Транспортный Завод Улучшите Лагерь Стрелков до 16-го Ур. " +
                "Мир Герои Квесты Сумка Почта Альянс Ещё"
        )
        assertEquals("city", result.getString("category"))
        assertFalse(result.getBoolean("actionable"))
    }

    @Test fun recognizesCityWhenOnlySparsePassContainsMapLabels() {
        val sparsePass = "Стройка. Наука Штаб Лагерь Стрелко Лагерь Наездн " +
            "Бойц Улучшите Ла рь Стрелков до Ур. Почта"
        val autoPass = "= 743к 41.90м 5 700к 4э1к пери 17 начал(а) " +
            "групповую атаку на Логой Почта НОВ Е Ещё"

        val result = GameScreenClassifier.classify("$sparsePass\\n$autoPass")

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

    @Test fun recognizesTheObservedRedmiCityScreenLabels() {
        val result = GameScreenClassifier.classify(
            "Стройка Наука Яма Сторожевая Башня Штаб-Квартира " +
                "Лагерь Стрелков Транспортный Завод Улучшите Лагерь Стрелков до 16-го ур."
        )
        assertEquals("city", result.getString("category"))
        assertFalse(result.getBoolean("actionable"))
    }

    @Test fun recognizesObservedHeadquartersDetailsScreen() {
        val result = GameScreenClassifier.classify(
            "Данные здания Штаб-Квартира это ключевое здание для убежища " +
                "Улучшите её чтобы открыть новые постройки и увеличить предельный уровень зданий " +
                "Эффект Уровень Размер Отряда Вместимость Лазарета Разблокирует Откроет Кампанию"
        )
        assertEquals("building", result.getString("category"))
        assertFalse(result.getBoolean("actionable"))
    }

    @Test fun recognizesTheObservedRussianQuestScreen() {
        val result = GameScreenClassifier.classify(
            "Квест Основной Квест Суточный Квест Активность Альянса Рекомендация " +
                "Улучшите Лагерь Стрелков до 16-го Ур. Битва Пройти 110 стадию " +
                "Главного Испытания Охота Одолейте 3 Зомби 5-го Ур. Война " +
                "Обучите Бойца Прирост Имейте в наличии 3 героев 211-го Ур. Экономика Соберите Еду"
        )
        assertEquals("quests", result.getString("category"))
        assertFalse(result.getBoolean("actionable"))
    }

    @Test fun recognizesQuestFromNoisyRussianTesseractOutput() {
        val result = GameScreenClassifier.classify(
            "Основной Суточный Активность ИлЕянса Рекомендация " +
                "Улучшите Лагерь Стрелков до 16-го Ур. Битва " +
                "Пройти 110 стадию Главного испытания Охота " +
                "Одолейте 3 Зомби 5-го Ур. Война Обучите Бойца " +
                "Прирост Имейте в наличии 3 героев 211-го Ур. Экономика Соберите Еду"
        )
        assertEquals("quests", result.getString("category"))
        assertFalse(result.getBoolean("actionable"))
    }

    @Test fun leavesScarlettPurchaseOfferUnknown() {
        val result = GameScreenClassifier.classify(
            "Набор Скарлетт 3 дня Макс эффект Скорость строительства " +
                "Бесплатное ускорение 80 мин Можно купить только один набор 449 рублей"
        )
        assertEquals("unknown", result.getString("category"))
        assertFalse(result.getBoolean("actionable"))
    }

    @Test fun handlesEmptyOcr() {
        val result = GameScreenClassifier.classify("")
        assertEquals("unknown", result.getString("category"))
        assertEquals("empty_ocr_text", result.getString("reason"))
    }
}
