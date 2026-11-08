package org.miquelmartin.blinky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PhoneActionsTest {
    @Test fun failedFetchNeverCopiesEvenWithAnOldToken() {
        var copied = false
        assertEquals(TokenCopyResult.FETCH_FAILED, copyFetchedToken(false, "old-token") { copied = true })
        assertFalse(copied)
    }

    @Test fun missingOrBlankTokenNeverCopies() {
        for (token in listOf(null, "", "  ")) {
            assertEquals(TokenCopyResult.FETCH_FAILED, copyFetchedToken(true, token) { error("Must not copy.") })
        }
    }

    @Test fun successCopiesExactlyTheFetchedToken() {
        var copied: String? = null
        assertEquals(TokenCopyResult.COPIED, copyFetchedToken(true, "current-token") { copied = it })
        assertEquals("current-token", copied)
    }

    @Test fun clipboardErrorsAreDistinctFromFetchErrors() {
        assertEquals(TokenCopyResult.COPY_FAILED, copyFetchedToken(true, "current-token") { throw SecurityException() })
        assertEquals(TokenCopyResult.COPY_FAILED, copyFetchedToken(true, "current-token") { throw IllegalStateException() })
    }

    @Test fun permissionIsRequestedOnlyWhileAndroidPermitsIt() {
        assertEquals(PermissionAction.REQUEST, permissionAction(true, false, false, false, false))
        assertEquals(PermissionAction.REQUEST, permissionAction(true, false, true, true, false))
        assertEquals(PermissionAction.SETTINGS, permissionAction(true, false, true, false, false))
    }

    @Test fun disabledChannelAndOlderAndroidUseSettingsNotRuntimePrompts() {
        assertEquals(PermissionAction.SETTINGS, permissionAction(true, true, true, false, false))
        assertEquals(PermissionAction.SETTINGS, permissionAction(false, true, false, false, false))
        assertEquals(PermissionAction.NONE, permissionAction(true, true, true, false, true))
    }
}
