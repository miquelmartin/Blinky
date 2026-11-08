package org.miquelmartin.blinky

enum class TokenCopyResult { COPIED, FETCH_FAILED, COPY_FAILED }

fun copyFetchedToken(fetchSucceeded: Boolean, token: String?, copy: (String) -> Unit): TokenCopyResult {
    if (!fetchSucceeded || token.isNullOrBlank()) return TokenCopyResult.FETCH_FAILED
    return try {
        copy(token)
        TokenCopyResult.COPIED
    } catch (_: SecurityException) {
        TokenCopyResult.COPY_FAILED
    } catch (_: IllegalStateException) {
        TokenCopyResult.COPY_FAILED
    }
}

enum class PermissionAction { NONE, REQUEST, SETTINGS }

fun permissionAction(
    runtimePermissionApplies: Boolean,
    granted: Boolean,
    requestedBefore: Boolean,
    rationale: Boolean,
    notificationsEnabled: Boolean
): PermissionAction {
    if (!runtimePermissionApplies || granted) {
        return if (notificationsEnabled) PermissionAction.NONE else PermissionAction.SETTINGS
    }
    return if (!requestedBefore || rationale) PermissionAction.REQUEST else PermissionAction.SETTINGS
}
