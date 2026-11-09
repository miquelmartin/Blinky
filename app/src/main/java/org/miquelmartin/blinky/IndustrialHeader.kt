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
    private val openFace = BitmapFactory.decodeResource(resources, R.drawable.blinky_mascot)
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
    private val eyes = Path().apply {
        addOval(31.2f, 37.2f, 52.8f, 64.8f, Path.Direction.CW)
        addOval(55.2f, 37.2f, 76.8f, 64.8f, Path.Direction.CW)
    }
    private val seams = Path().apply {
        moveTo(36f, 51f)
        quadTo(42f, 53f, 48f, 51f)
        moveTo(60f, 51f)
        quadTo(66f, 53f, 72f, 51f)
    }
    private var closure = 0f
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
        if (mayAnimate()) blink()
    }

    init {
        contentDescription = context.getString(R.string.app_name)
    }

    fun setActive(value: Boolean) {
        active = value
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
            else if (!scheduled && animator == null) schedule(nextBlinkTiming().periodMillis)
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
        closure = 0f
        invalidate()
    }

    private fun blink() {
        val timing = nextBlinkTiming()
        val started = SystemClock.uptimeMillis()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = timing.durationMillis
            interpolator = LinearInterpolator()
            addUpdateListener {
                closure = blinkClosure(it.animatedValue as Float)
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    animator = null
                    closure = 0f
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
        val eyeWindow = canvas.save()
        canvas.clipPath(eyes)
        canvas.clipRect(20f, 37f + 14.35f * closure, 88f, 65f - 14.35f * closure)
        canvas.drawBitmap(openFace, null, faceBounds, paint)
        canvas.restoreToCount(eyeWindow)
        paint.color = 0xFFB83942.toInt()
        paint.alpha = (255 * closure.pow(10)).roundToInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.3f
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawPath(seams, paint)
        canvas.restoreToCount(ghost)
        val baseline = PUPIL_Y - (textBounds.top + textBounds.bottom) / 2f
        val top = baseline + textBounds.top
        val bottom = baseline + textBounds.bottom
        val shutter = canvas.save()
        // Clip the glyphs themselves; no background-colored shutter or nameplate.
        canvas.clipRect(141f, top + textBounds.height() * closure / 2, width / scale, bottom - textBounds.height() * closure / 2)
        canvas.drawText(text, 141f - textBounds.left, baseline, wordmark)
        canvas.restoreToCount(shutter)
        canvas.restoreToCount(scene)
    }

    @android.annotation.TargetApi(33)
    private class AnimatorScaleWatcher(changed: () -> Unit) {
        private val listener = ValueAnimator.DurationScaleChangeListener { changed() }
        fun start() { ValueAnimator.registerDurationScaleChangeListener(listener) }
        fun stop() { ValueAnimator.unregisterDurationScaleChangeListener(listener) }
    }
}
