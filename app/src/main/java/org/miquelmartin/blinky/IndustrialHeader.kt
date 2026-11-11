package org.miquelmartin.blinky

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.database.ContentObserver
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

private const val PUPIL_Y = 83.4f

class IndustrialHeader(context: Context, brand: IndustrialBrandView, private val action: View) : ViewGroup(context) {
    init {
        clipChildren = false
        clipToPadding = false
        addView(brand)
        addView(action)
    }

    private fun scale(width: Int) = min(width / 400f, resources.displayMetrics.density * 1.25f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val scale = scale(width)
        setMeasuredDimension(width, (160 * scale).roundToInt())
        getChildAt(0).measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec((208 * scale).roundToInt(), MeasureSpec.EXACTLY)
        )
        val size = maxOf(50 * scale, 48 * resources.displayMetrics.density).roundToInt()
        action.measure(MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        getChildAt(0).layout(0, 0, measuredWidth, getChildAt(0).measuredHeight)
        val scale = scale(measuredWidth)
        val x = measuredWidth - (24 * scale).roundToInt() - action.measuredWidth
        val y = (PUPIL_Y * scale - action.measuredHeight / 2f).roundToInt()
        action.layout(x, y, x + action.measuredWidth, y + action.measuredHeight)
    }
}

class IndustrialBrandView(context: Context) : View(context) {
    private val closedFace = BitmapFactory.decodeResource(resources, R.drawable.blinky_eyelid_base)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val wordmark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PocketColors.TEXT
        typeface = resources.getFont(R.font.barlow_semi_condensed_bold)
        textSize = 38f
    }
    private val text = context.getString(R.string.app_name).uppercase(java.util.Locale.ROOT)
    private val textBounds = Rect().also { wordmark.getTextBounds(text, 0, text.length, it) }
    private val faceBounds = RectF(20f, 14f, 88f, 96f)
    internal val expressionState = GhostExpressionState()
    private var frame = expressionState.frame(0f)
    private val eyePath = Path()
    private val seam = Path()
    private var active = false
    private var observing = false
    private var scheduled = false
    private var animator: ValueAnimator? = null
    private val scaleChanged = Runnable { reconcile() }
    private val scaleWatcher = if (Build.VERSION.SDK_INT >= 33) {
        AnimatorScaleWatcher { post(scaleChanged) }
    } else null
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = reconcile()
    }
    private val nextBlink = Runnable {
        scheduled = false
        if (mayAnimate()) animateExpression(nextExpression(expressionState.previous))
    }

    init {
        contentDescription = context.getString(R.string.app_name)
    }

    fun setActive(value: Boolean) {
        active = value
        reconcile()
    }

    fun restoreExpressionState(visible: Boolean, previous: GhostExpression?) {
        stopCycle()
        expressionState.wordmarkVisible = visible
        expressionState.previous = previous
        frame = expressionState.frame(0f)
        invalidate()
        reconcile()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        reconcile()
    }

    override fun onDetachedFromWindow() {
        stopCycle()
        stopObserving()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        reconcile()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        reconcile()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        reconcile()
    }

    private fun visible() = active && isAttachedToWindow && windowVisibility == VISIBLE && isShown
    private fun mayAnimate() = visible() &&
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f &&
        ValueAnimator.areAnimatorsEnabled()

    private fun reconcile() {
        if (visible()) {
            if (!observing) {
                context.contentResolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer
                )
                if (Build.VERSION.SDK_INT >= 33) scaleWatcher?.start()
                observing = true
            }
            if (!mayAnimate()) stopCycle()
            else if (!scheduled && animator == null) schedule(Random.nextLong(5_000, 10_001))
        } else {
            stopCycle()
            stopObserving()
        }
    }

    private fun schedule(delay: Long) {
        scheduled = true
        postDelayed(nextBlink, delay)
    }

    private fun stopObserving() {
        if (observing) {
            context.contentResolver.unregisterContentObserver(observer)
            if (Build.VERSION.SDK_INT >= 33) scaleWatcher?.stop()
            observing = false
        }
    }

    private fun stopCycle() {
        removeCallbacks(nextBlink)
        scheduled = false
        removeCallbacks(scaleChanged)
        animator?.removeAllListeners()
        animator?.cancel()
        animator = null
        expressionState.finish()
        frame = expressionState.frame(0f)
        invalidate()
    }

    private fun animateExpression(expression: GhostExpression) {
        check(animator == null && expressionState.current == null) { "Expressions must not overlap." }
        expressionState.start(expression, Random.nextBoolean())
        val timing = nextExpressionTiming(expression)
        val started = SystemClock.uptimeMillis()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = timing.durationMillis
            interpolator = LinearInterpolator()
            addUpdateListener {
                frame = expressionState.frame(it.animatedValue as Float)
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    animator = null
                    expressionState.finish()
                    frame = expressionState.frame(0f)
                    invalidate()
                    if (mayAnimate()) {
                        schedule((timing.periodMillis - (SystemClock.uptimeMillis() - started)).coerceAtLeast(0))
                    }

                }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = min(width / 400f, resources.displayMetrics.density * 1.25f)
        val scene = canvas.save()
        canvas.scale(scale, scale)
        val ghost = canvas.save()
        // The 90%-size SVG frame includes 5.67 units of vertical letterboxing.
        canvas.translate(-15.12f, 9.69f)
        canvas.scale(1.89f, 1.89f)
        canvas.translate(-20f, -14f)
        paint.style = Paint.Style.FILL
        paint.alpha = 255
        canvas.drawBitmap(closedFace, null, faceBounds, paint)
        drawEye(canvas, 42f, frame.leftClosure, frame.leftPupilX, frame.pupilY)
        drawEye(canvas, 66f, frame.rightClosure, frame.rightPupilX, frame.pupilY)
        canvas.restoreToCount(ghost)
        val baseline = PUPIL_Y - (textBounds.top + textBounds.bottom) / 2f
        val top = baseline + textBounds.top
        val bottom = baseline + textBounds.bottom
        val shutter = canvas.save()
        // Clip the glyphs themselves; no background-colored shutter or nameplate.
        val hidden = 1f - frame.wordmarkAmount
        canvas.clipRect(141f, top + textBounds.height() * hidden / 2, width / scale, bottom - textBounds.height() * hidden / 2)
        canvas.drawText(text, 141f - textBounds.left, baseline, wordmark)
        canvas.restoreToCount(shutter)
        canvas.restoreToCount(scene)
    }

    private fun drawEye(canvas: Canvas, center: Float, closure: Float, pupilX: Float, pupilY: Float) {
        val saved = canvas.save()
        eyePath.reset()
        eyePath.addOval(center - 10f, 38f, center + 10f, 64f, Path.Direction.CW)
        canvas.clipPath(eyePath)
        val topLid = expressionState.current == GhostExpression.SLEEPY_LIDS ||
            expressionState.current == GhostExpression.SKEPTICAL
        eyePath.reset()
        if (topLid) {
            val lid = 37f + 27f * closure
            eyePath.moveTo(center - 11f, lid)
            eyePath.quadTo(center, lid + closure * 3f, center + 11f, lid)
            eyePath.lineTo(center + 11f, 65f)
            eyePath.lineTo(center - 11f, 65f)
            eyePath.close()
        } else if (closure < 1f) {
            // Never pass an inverted rectangle to the renderer: it can normalize
            // the bounds and leave a bright slit at complete closure.
            val halfHeight = 13f * (1f - closure)
            eyePath.addOval(center - 10f, 51f - halfHeight, center + 10f, 51f + halfHeight, Path.Direction.CW)
        }
        canvas.clipPath(eyePath)
        paint.style = Paint.Style.FILL
        paint.alpha = 255
        paint.color = android.graphics.Color.WHITE
        canvas.drawOval(center - 10f, 38f, center + 10f, 64f, paint)
        val x = center + 4f + pupilX
        val y = 53f + pupilY
        paint.color = 0xFF285CE3.toInt()
        val pupilScale = frame.pupilScale
        canvas.drawOval(x - 5f * pupilScale, y - 7f * pupilScale, x + 5f * pupilScale, y + 7f * pupilScale, paint)
        paint.color = android.graphics.Color.WHITE
        canvas.drawCircle(x - 1.5f * pupilScale, y - 3f * pupilScale, 1.6f * pupilScale, paint)
        canvas.restoreToCount(saved)
        seam.reset()
        seam.moveTo(center - 6f, 51f)
        seam.quadTo(center, 53.5f, center + 6f, 51f)
        paint.color = 0xFFB83942.toInt()
        paint.alpha = if (topLid) 0 else (255 * closure.pow(6)).roundToInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.3f
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawPath(seam, paint)
    }

    @android.annotation.TargetApi(33)
    private class AnimatorScaleWatcher(changed: () -> Unit) {
        private val listener = ValueAnimator.DurationScaleChangeListener { changed() }
        fun start() { ValueAnimator.registerDurationScaleChangeListener(listener) }
        fun stop() { ValueAnimator.unregisterDurationScaleChangeListener(listener) }
    }
}
