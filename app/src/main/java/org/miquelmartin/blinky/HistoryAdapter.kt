package org.miquelmartin.blinky

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.TextView
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

object PocketColors {
    const val HEADER = 0xFF202E44.toInt()
    const val HISTORY = 0xFF26364D.toInt()
    const val TEXT = 0xFFE9EDF5.toInt()
    const val BODY = 0xFFB8C4D5.toInt()
    const val MUTED = 0xFFA5B4C8.toInt()
    const val BADGE = 0xFF34465F.toInt()
    const val LINE = 0xFF44566D.toInt()
    const val DOT = 0xFF718299.toInt()
    const val CORAL = 0xFFF77A70.toInt()
}

class HistoryAdapter(private val context: Context) : BaseAdapter() {
    private var rows: List<HistoryRow> = emptyList()
    private var zone = ZoneId.systemDefault()

    fun update(messages: List<ReceivedNotification>) {
        zone = ZoneId.systemDefault()
        rows = historyRows(messages, zone)
        notifyDataSetChanged()
    }

    override fun getCount() = rows.size
    override fun getItem(position: Int) = rows[position]
    override fun getItemId(position: Int) = position.toLong()
    override fun getViewTypeCount() = 2
    override fun getItemViewType(position: Int) = if (rows[position] is HistoryRow.Day) 0 else 1
    override fun isEnabled(position: Int) = false

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        return when (val row = rows[position]) {
            is HistoryRow.Day -> {
                val view = (convertView as? TextView) ?: TextView(context).apply {
                    textSize = 11f
                    setTextColor(PocketColors.MUTED)
                    typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    letterSpacing = 0.12f
                    setPadding(dp(24), dp(22), dp(24), dp(16))
                }
                val today = LocalDate.now(zone)
                view.text = when (row.date) {
                    today -> context.getString(R.string.history_today)
                    today.minusDays(1) -> context.getString(R.string.history_yesterday)
                    else -> row.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
                }.uppercase(Locale.getDefault())
                view
            }
            is HistoryRow.Message -> {
                val view = (convertView as? MessageView) ?: MessageView(context)
                view.bind(row, zone)
                view
            }
        }
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private class MessageView(context: Context) : LinearLayout(context) {
        private val timeline = Timeline(context)
        private val title = TextView(context).apply {
            textSize = 17f
            setTextColor(PocketColors.TEXT)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        private val time = TextView(context).apply {
            textSize = 11f
            setTextColor(PocketColors.MUTED)
            gravity = Gravity.END
            setPadding(dp(8), dp(4), 0, 0)
        }
        private val body = TextView(context).apply {
            textSize = 14f
            setTextColor(PocketColors.BODY)
            setLineSpacing(dp(5).toFloat(), 1f)
            setPadding(0, dp(5), 0, dp(20))
        }
        private val divider = View(context).apply { setBackgroundColor(PocketColors.BADGE) }

        init {
            orientation = HORIZONTAL
            setPadding(dp(24), 0, dp(24), 0)
            addView(timeline, LayoutParams(dp(20), LayoutParams.MATCH_PARENT))
            val content = LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(dp(13), 0, 0, 0)
            }
            val heading = LinearLayout(context).apply { orientation = HORIZONTAL }
            heading.addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            heading.addView(time, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            content.addView(heading)
            content.addView(body)
            content.addView(divider, LayoutParams(LayoutParams.MATCH_PARENT, dp(1)).apply {
                bottomMargin = dp(20)
            })
            addView(content, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }

        fun bind(row: HistoryRow.Message, zone: ZoneId) {
            title.text = row.notification.title
            body.text = row.notification.body
            time.text = android.text.format.DateFormat.getTimeFormat(context).format(row.notification.receivedAt)
            time.contentDescription = java.time.Instant.ofEpochMilli(row.notification.receivedAt)
                .atZone(zone).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM))
            divider.visibility = if (row.lastInDay) GONE else VISIBLE
            timeline.row = row
            timeline.invalidate()
        }

        private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    }

    private class Timeline(context: Context) : View(context) {
        var row: HistoryRow.Message? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val entry = row ?: return
            val density = resources.displayMetrics.density
            val x = width / 2f
            val y = 14 * density
            paint.color = PocketColors.LINE
            paint.strokeWidth = density
            canvas.drawLine(x, if (entry.firstInDay) y else 0f, x, if (entry.lastInDay) y else height.toFloat(), paint)
            paint.color = if (entry.latest) PocketColors.CORAL else PocketColors.DOT
            canvas.drawCircle(x, y, 6 * density, paint)
            if (entry.latest) {
                paint.color = PocketColors.HISTORY
                canvas.drawCircle(x, y, 3 * density, paint)
            }
        }
    }
}
