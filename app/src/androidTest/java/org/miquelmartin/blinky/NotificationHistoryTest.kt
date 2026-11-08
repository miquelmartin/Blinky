package org.miquelmartin.blinky

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationHistoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "blinky_history_test.db"

    @Before fun reset() {
        context.deleteDatabase(databaseName)
        context.getSharedPreferences("blinky_history_test", 0).edit().clear().commit()
    }

    @After fun cleanup() {
        context.deleteDatabase(databaseName)
        context.getSharedPreferences("blinky_history_test", 0).edit().clear().commit()
    }

    @Test fun actualFieldsPersistAcrossDatabaseReopen() {
        NotificationHistory(context, databaseName).use {
            it.append("First: title", "Line one\nLine two \uD83D\uDC7B", 100)
            it.append("Second", "Don't lose apostrophes", 200)
        }
        NotificationHistory(context, databaseName).use {
            val rows = it.read()
            assertEquals(2, rows.size)
            assertEquals("Second", rows[0].title)
            assertEquals("First: title", rows[1].title)
            assertEquals("Line one\nLine two \uD83D\uDC7B", rows[1].body)
            assertEquals(100L, rows[1].receivedAt)
        }
    }

    @Test fun addingHistoryDoesNotChangeExistingInstallationStateOrInventOldTimestamps() {
        val preferences = context.getSharedPreferences("blinky_history_test", 0)
        preferences.edit()
            .putString(BlinkyState.TOKEN, "existing-installation-token")
            .putString(BlinkyState.LAST_MESSAGE, "Old title: old body")
            .commit()
        NotificationHistory(context, databaseName).use { assertTrue(it.read().isEmpty()) }
        assertEquals("existing-installation-token", preferences.getString(BlinkyState.TOKEN, null))
        assertEquals("Old title: old body", preferences.getString(BlinkyState.LAST_MESSAGE, null))
    }
}
