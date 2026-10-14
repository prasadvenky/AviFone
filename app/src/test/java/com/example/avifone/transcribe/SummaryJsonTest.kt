package com.example.avifone.transcribe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryJsonTest {

    @Test
    fun parsesCleanJson() {
        val s = SummaryJson.parse(
            """{"topic":"Lunch plans","points":["Ana wants sushi","Ben prefers tacos"],"actionItems":["Book a table"]}""",
            nowMs = 42,
        )
        assertEquals("Lunch plans", s.topic)
        assertEquals(listOf("Ana wants sushi", "Ben prefers tacos"), s.points)
        assertEquals(listOf("Book a table"), s.actionItems)
        assertEquals(42L, s.updatedAtMs)
    }

    @Test
    fun stripsFencesAndSurroundingProse() {
        val s = SummaryJson.parse(
            "Here is the summary:\n```json\n{\"topic\": \"Budget\", \"points\": [\"Cut travel\"], \"actionItems\": []}\n```\nHope this helps!",
            nowMs = 0,
        )
        assertEquals("Budget", s.topic)
        assertEquals(listOf("Cut travel"), s.points)
        assertTrue(s.actionItems.isEmpty())
    }

    @Test
    fun ignoresThinkBlocks() {
        val s = SummaryJson.parse(
            "<think>\nThe users talk about {braces} in a plan.\n</think>\n{\"topic\":\"Plan\",\"points\":[\"One\"],\"actionItems\":[]}",
            nowMs = 0,
        )
        assertEquals("Plan", s.topic)
        assertEquals(listOf("One"), s.points)
    }

    @Test
    fun acceptsKeyAliasesAndObjectItems() {
        val s = SummaryJson.parse(
            """{"topic":"X","key_points":[{"text":"A"},{"point":"B"}],"action_items":["Do C"]}""",
            nowMs = 0,
        )
        assertEquals(listOf("A", "B"), s.points)
        assertEquals(listOf("Do C"), s.actionItems)
    }

    @Test
    fun fallsBackToBulletLines() {
        val s = SummaryJson.parse("- First thing\n• Second thing\n3. Third thing\n\n", nowMs = 0)
        assertEquals("", s.topic)
        assertEquals(listOf("First thing", "Second thing", "Third thing"), s.points)
    }

    @Test
    fun emptyAnswerGivesEmptySummary() {
        val s = SummaryJson.parse("", nowMs = 0)
        assertEquals("", s.topic)
        assertTrue(s.points.isEmpty())
        assertTrue(s.actionItems.isEmpty())
    }

    @Test
    fun malformedJsonFallsBackInsteadOfThrowing() {
        val s = SummaryJson.parse("{\"topic\": \"Broken\", \"points\": [\"unterminated", nowMs = 0)
        assertTrue(s.points.isNotEmpty())
    }
}
