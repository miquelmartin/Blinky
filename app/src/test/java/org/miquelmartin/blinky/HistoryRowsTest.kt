package org.miquelmartin.blinky

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryRowsTest {
    private fun message(id: Long, timestamp: String) =
        ReceivedNotification(id, "Title", "Body", Instant.parse(timestamp).toEpochMilli())

    @Test fun emptyHistoryHasNoInventedRows() {
        assertTrue(historyRows(emptyList(), ZoneId.of("UTC")).isEmpty())
    }

    @Test fun newestFirstWithLocalDayGroupingAndStableEqualTimeOrder() {
        val messages = listOf(
            message(1, "2026-11-08T22:30:00Z"),
            message(2, "2026-11-08T23:30:00Z"),
            message(3, "2026-11-08T23:30:00Z")
        )
        val rows = historyRows(messages, ZoneId.of("Europe/Madrid"))
        assertEquals(HistoryRow.Day(LocalDate.of(2026, 11, 9)), rows[0])
        assertEquals(HistoryRow.Message(messages[2], true, true, false), rows[1])
        assertEquals(HistoryRow.Message(messages[1], false, false, true), rows[2])
        assertEquals(HistoryRow.Day(LocalDate.of(2026, 11, 8)), rows[3])
        assertEquals(HistoryRow.Message(messages[0], false, true, true), rows[4])
    }

    @Test fun daylightSavingOverlapStaysInOneDayAndPreservesContent() {
        val first = message(1, "2026-10-25T00:30:00Z").copy(title = "Title: with colon", body = "Line one\nLine two")
        val second = message(2, "2026-10-25T01:30:00Z")
        val rows = historyRows(listOf(first, second), ZoneId.of("Europe/Madrid"))
        assertEquals(3, rows.size)
        assertEquals(first, (rows[2] as HistoryRow.Message).notification)
    }
}
