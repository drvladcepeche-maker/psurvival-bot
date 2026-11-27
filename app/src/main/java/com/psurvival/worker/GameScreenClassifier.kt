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

    private val rules = listOf(
        Rule("city", listOf("стро", "улучш", "казарм", "склад", "ферм", "завод", "лагер", "стрелков", "наук", "транспорт", "лаборатор", "штаб-квартир", "база", "город", "building", "upgrade", "barracks", "warehouse", "farm", "headquarters"), 4),
        Rule("quests", listOf("задан", "ежеднев", "награ", "мисси", "активност", "получить", "daily", "quest", "mission", "claim reward"), 4),
        Rule("map", listOf("мир", "карта", "координат", "ресурсная точка", "сбор", "пустош", "world", "map", "gather", "march"), 4),
        Rule("heroes", listOf("геро", "оскол", "навык", "формац", "звезд", "heroes", "hero", "shards", "formation", "skill"), 4),
        Rule("alliance", listOf("альянс", "участник", "помощь альянса", "технолог", "донат альянса", "rally", "alliance", "members"), 4),
        Rule("mail", listOf("почта", "письмо", "входящие", "отправитель", "mail", "inbox", "sender"), 4),
        Rule("events", listOf("событи", "ивент", "этап", "очки события", "рейтинг", "event", "leaderboard", "ranking"), 4),
        Rule("battle", listOf("бой", "атака", "защит", "противник", "побед", "поражен", "battle", "attack", "enemy", "victory"), 4)
    )

    fun classify(rawText: String): JSONObject {
        val normalized = normalize(rawText)
        val scores = rules.map { rule ->
            val matched = rule.keywords.filter { normalized.contains(normalize(it)) }
            Triple(rule, matched, matched.sumOf { if (it.length >= 7) 2 else 1 })
        }.sortedByDescending { it.third }

        val best = scores.firstOrNull()
        val secondScore = scores.getOrNull(1)?.third ?: 0
        val accepted = best != null && best.third >= best.first.minScore && best.third - secondScore >= 1
        val result = JSONObject()
            .put("classifier", "pns_keyword_rules_v1")
            .put("category", if (accepted) best!!.first.category else "unknown")
            .put("confidence", if (accepted) confidence(best!!.third, secondScore) else 0.0)
            .put("actionable", false)
            .put("reason", when {
                normalized.isBlank() -> "empty_ocr_text"
                best == null || best.third == 0 -> "no_matching_evidence"
                !accepted -> "low_or_ambiguous_score"
                else -> "matched_keywords"
            })

        val evidence = JSONArray()
        if (best != null) best.second.forEach { evidence.put(it) }
        result.put("evidence", evidence)
        val ranked = JSONArray()
        scores.filter { it.third > 0 }.take(4).forEach { (rule, matched, score) ->
            ranked.put(JSONObject()
                .put("category", rule.category)
                .put("score", score)
                .put("matches", JSONArray(matched)))
        }
        result.put("alternatives", ranked)
        return result
    }

    private fun normalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace('ё', 'е')
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

    private fun confidence(best: Int, second: Int): Double {
        val separation = (best - second).coerceAtLeast(0)
        return (0.55 + best.coerceAtMost(10) * 0.035 + separation.coerceAtMost(5) * 0.025)
            .coerceAtMost(0.92)
    }
}
