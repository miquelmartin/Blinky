package org.miquelmartin.blinky

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class ReceivedNotification(val id: Long, val title: String, val body: String, val receivedAt: Long)

sealed interface HistoryRow {
    data class Day(val date: LocalDate) : HistoryRow
    data class Message(
        val notification: ReceivedNotification,
        val latest: Boolean,
        val firstInDay: Boolean,
        val lastInDay: Boolean
    ) : HistoryRow
}

fun historyRows(messages: List<ReceivedNotification>, zone: ZoneId): List<HistoryRow> {
    val sorted = messages.sortedWith(compareByDescending<ReceivedNotification> { it.receivedAt }.thenByDescending { it.id })
    val days = sorted.groupBy { Instant.ofEpochMilli(it.receivedAt).atZone(zone).toLocalDate() }
    return buildList {
        days.forEach { (day, entries) ->
            add(HistoryRow.Day(day))
            entries.forEachIndexed { index, message ->
                add(HistoryRow.Message(message, message.id == sorted.first().id, index == 0, index == entries.lastIndex))
            }
        }
    }
}
