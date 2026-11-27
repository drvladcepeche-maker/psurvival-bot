package com.psurvival.worker

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Conservative, text-only classifier for Puzzles & Survival screens.
 * It reports evidence and uncertainty; it never authorizes or performs an action.
 */
internal object GameScreenClassifier {
    private data class Rule(val category: String, val keywords: List<String>, val minScore: Int)
    private data class ScoredRule(val rule: Rule, val matches: List<String>, val score: Int)

    private val rules = listOf(
        Rule("city", listOf("стро", "стром", "улучш", "казарм", "склад", "ферм", "завод", "лагер", "стрелков", "стрепк", "наук", "транспорт", "лаборатор", "штаб-квартир", "база", "город", "building", "upgrade", "barracks", "warehouse", "farm", "headquarters"), 3),
        Rule("quests", listOf("квест", "основной квест", "суточный квест", "активность альянса", "рекомендация", "задан", "ежеднев", "награ", "мисси", "активност", "получить", "daily", "quest", "mission", "claim reward"), 4),
        Rule("map", listOf("мир", "карта", "координат", "ресурсная точка", "сбор", "пустош", "world", "map", "gather", "march"), 4),
        Rule("heroes", listOf("геро", "оскол", "навык", "формац", "звезд", "heroes", "hero", "shards", "formation", "skill"), 4),
        Rule("alliance", listOf("альянс", "участник", "помощь альянса", "технолог", "донат альянса", "rally", "alliance", "members"), 4),
        Rule("mail", listOf("почта", "письмо", "входящие", "отправитель", "mail", "inbox", "sender"), 4),
        Rule("events", listOf("событи", "ивент", "этап", "очки события", "рейтинг", "event", "leaderboard", "ranking"), 4),
        Rule("battle", listOf("бой", "атака", "защит", "противник", "побед", "поражен", "battle", "attack", "enemy", "victory"), 4)
    )

    fun classify(rawText: String): JSONObject {
        val normalized = normalize(rawText)
        val scores: List<ScoredRule> = rules.map { rule ->
            val matches = rule.keywords.filter { normalized.contains(normalize(it)) }
            val score = matches.fold(0) { total, keyword -> total + if (keyword.length >= 7) 2 else 1 }
            ScoredRule(rule, matches, score)
        }.sortedByDescending { it.score }

        val best = scores.firstOrNull()
        val secondScore = scores.getOrNull(1)?.score ?: 0
        val accepted = best != null &&
            best.score >= best.rule.minScore &&
            best.score - secondScore >= 1

        val result = JSONObject()
            .put("classifier", "pns_keyword_rules_v1")
            .put("category", if (accepted) best!!.rule.category else "unknown")
            .put("confidence", if (accepted) confidence(best!!.score, secondScore) else 0.0)
            .put("actionable", false)
            .put("reason", when {
                normalized.isBlank() -> "empty_ocr_text"
                best == null || best.score == 0 -> "no_matching_evidence"
                !accepted -> "low_or_ambiguous_score"
                else -> "matched_keywords"
            })

        val evidence = JSONArray()
        best?.matches?.forEach { evidence.put(it) }
        result.put("evidence", evidence)

        val ranked = JSONArray()
        scores.filter { it.score > 0 }.take(4).forEach { scored ->
            ranked.put(JSONObject()
                .put("category", scored.rule.category)
                .put("score", scored.score)
                .put("matches", JSONArray(scored.matches)))
        }
        result.put("alternatives", ranked)
        return result
    }

    private fun normalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace('ё', 'е')
        // ML Kit often recognizes Cyrillic UI glyphs as visually similar Latin letters.
        .replace('a', 'а')
        .replace('b', 'в')
        .replace('c', 'с')
        .replace('e', 'е')
        .replace('h', 'н')
        .replace('k', 'к')
        .replace('m', 'м')
        .replace('o', 'о')
        .replace('p', 'р')
        .replace('t', 'т')
        .replace('x', 'х')
        .replace('y', 'у')
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

    private fun confidence(best: Int, second: Int): Double {
        val separation = (best - second).coerceAtLeast(0)
        return (0.55 + best.coerceAtMost(10) * 0.035 + separation.coerceAtMost(5) * 0.025)
            .coerceAtMost(0.92)
    }
}
