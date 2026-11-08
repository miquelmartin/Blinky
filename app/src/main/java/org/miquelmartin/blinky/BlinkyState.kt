package org.miquelmartin.blinky

import android.content.Context
import android.content.SharedPreferences

object BlinkyState {
    const val TOKEN = "token"
    const val LAST_MESSAGE = "last_message"
    const val LAST_DISPLAY = "last_display"
    const val DISPLAYED = "displayed"
    const val SUPPRESSED = "suppressed"
    const val FAILED = "failed"

    fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences("blinky", Context.MODE_PRIVATE)
}
