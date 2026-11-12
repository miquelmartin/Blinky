package org.miquelmartin.blinky

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.ParcelFileDescriptor
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IndustrialHeaderTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun wordmarkHasOnlyTransparentBackgroundAndLightGlyphInk() {
        instrumentation.runOnMainSync {
            val brand = IndustrialBrandView(instrumentation.targetContext)
            brand.layout(0, 0, 400, 208)
            val image = Bitmap.createBitmap(400, 208, Bitmap.Config.ARGB_8888)
            brand.draw(Canvas(image))
            var ink = 0
            for (x in 135..285) for (y in 70..125) {
                val color = image.getPixel(x, y)
                if (Color.alpha(color) > 16) {
                    assertTrue("Unexpected wordmark background", Color.red(color) > 200 && Color.green(color) > 200)
                    ink++
                }
            }
            assertTrue(ink > 500)
            assertEquals(0, Color.alpha(image.getPixel(135, 75)))
            assertTrue(Color.red(image.getPixel(50, 145)) > Color.blue(image.getPixel(50, 145)))
            for (x in 0..130) for (y in 159 until 208) {
                assertEquals("Mascot or shadow overlaps the history heading", 0, Color.alpha(image.getPixel(x, y)))
            }
            image.recycle()
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun backgroundingCancelsPendingAnimationAndResetsOpen() = withActivity { activity, brand ->
        awaitOnMain("The visible view must schedule an expression") {
            brand.handler.hasCallbacks(callback(brand))
        }
        instrumentation.runOnMainSync { activity.moveTaskToBack(true) }
        awaitOnMain("The activity must become hidden") {
            !brand.isShown || brand.windowVisibility != View.VISIBLE
        }
        instrumentation.runOnMainSync {
            assertFalse(brand.handler.hasCallbacks(callback(brand)))
            assertEquals(null, field("animator").get(brand))
            assertEquals(null, brand.expressionState.current)
            assertEquals(0f, currentFrame(brand).leftClosure, 0f)
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun repeatedVisibleCallbacksDoNotPostponeTheBlink() = withActivity { activity, brand ->
        instrumentation.runOnMainSync {
            brand.restoreExpressionState(true, null)
            brand.removeCallbacks(callback(brand))
            field("scheduled").setBoolean(brand, false)
            IndustrialBrandView::class.java.getDeclaredMethod("schedule", java.lang.Long.TYPE)
                .apply { isAccessible = true }.invoke(brand, 1_000L)
        }
        val deadline = SystemClock.uptimeMillis() + 3_000
        repeat(12) {
            instrumentation.runOnMainSync { brand.onVisibilityAggregated(true) }
            SystemClock.sleep(100)
        }
        var started = false
        while (!started && SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync { started = brand.expressionState.previous != null }
            SystemClock.sleep(30)
        }
        instrumentation.runOnMainSync {
            started = started || brand.expressionState.previous != null
            assertTrue(
                "Visibility callbacks postponed the scheduled expression " +
                    "(elapsed=${SystemClock.uptimeMillis() - (deadline - 3_000)}ms, " +
                    "queued=${brand.handler.hasCallbacks(callback(brand))}, " +
                    "scheduled=${field("scheduled").getBoolean(brand)}, " +
                    "attached=${brand.isAttachedToWindow}, shown=${brand.isShown}, " +
                    "windowVisibility=${brand.windowVisibility}, windowFocus=${activity.hasWindowFocus()}, " +
                    "active=${field("active").getBoolean(brand)}, " +
                    "animatorScale=${Settings.Global.getFloat(brand.context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, -1f)}, " +
                    "animatorsEnabled=${ValueAnimator.areAnimatorsEnabled()}, " +
                    "previous=${brand.expressionState.previous}, current=${brand.expressionState.current})",
                started
            )
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun removingAnimationsCancelsBlinkAndRestoresOpenWhileVisible() = withActivity { _, brand ->
        instrumentation.runOnMainSync {
            brand.restoreExpressionState(true, null)
            field("nextBlink").get(brand).let { brand.removeCallbacks(it as Runnable) }
            field("scheduled").setBoolean(brand, false)
            animate(brand, GhostExpression.BLINK)
            (field("animator").get(brand) as ValueAnimator).apply {
                pause()
                currentPlayTime = duration / 2
            }
            assertEquals(1f, currentFrame(brand).leftClosure, 0.0001f)
        }
        shell("settings put global animator_duration_scale 0")
        awaitOnMain("The native duration-scale notification must cancel the paused animator") {
            field("animator").get(brand) == null
        }
        instrumentation.runOnMainSync {
            assertEquals(0f, Settings.Global.getFloat(brand.context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE), 0f)
            assertFalse(brand.handler.hasCallbacks(callback(brand)))
            assertEquals(null, field("animator").get(brand))
            assertEquals(0f, currentFrame(brand).leftClosure, 0f)
            assertEquals(0f, currentFrame(brand).wordmarkAmount, 0f)
            assertFalse(brand.expressionState.wordmarkVisible)
        }

        shell("settings put global animator_duration_scale 1")
        awaitOnMain("Re-enabling animations must schedule another expression") {
            brand.handler.hasCallbacks(callback(brand))
        }
        instrumentation.runOnMainSync { assertTrue(brand.handler.hasCallbacks(callback(brand))) }
    }

    @Test fun everyExpressionRendersAndOnlyFullBlinksChangeTheWordmark() = withActivity { _, brand ->
        instrumentation.runOnMainSync {
            brand.setActive(false)
            brand.layout(0, 0, 400, 208)
            for (expression in GhostExpression.entries) {
                brand.restoreExpressionState(true, null)
                animate(brand, expression)
                val animator = field("animator").get(brand) as ValueAnimator
                animator.pause()
                for (step in 0..20) {
                    animator.currentPlayTime = animator.duration * step / 20
                    val image = Bitmap.createBitmap(400, 208, Bitmap.Config.ARGB_8888)
                    brand.draw(Canvas(image))
                    for (x in 0..130) for (y in 159 until 208) {
                        assertEquals("${expression.name} frame $step overlaps history at ($x,$y)", 0, Color.alpha(image.getPixel(x, y)))
                    }
                    if (!expression.togglesWordmark) assertEquals(1f, currentFrame(brand).wordmarkAmount, 0f)
                    image.recycle()
                }
                brand.setActive(false)
                assertEquals(!expression.togglesWordmark, brand.expressionState.wordmarkVisible)
                assertEquals(null, brand.expressionState.current)
                assertEquals(null, field("animator").get(brand))
                assertFalse(field("scheduled").getBoolean(brand))
            }
            brand.restoreExpressionState(false, GhostExpression.DOUBLE_BLINK)
            animate(brand, GhostExpression.BLINK)
            (field("animator").get(brand) as ValueAnimator).apply {
                pause()
                currentPlayTime = duration
            }
            assertEquals(1f, currentFrame(brand).wordmarkAmount, 0f)
            assertTrue(brand.expressionState.wordmarkVisible)
            val revealed = Bitmap.createBitmap(400, 208, Bitmap.Config.ARGB_8888)
            brand.draw(Canvas(revealed))
            var ink = 0
            for (x in 135..285) for (y in 70..125) if (Color.alpha(revealed.getPixel(x, y)) > 16) ink++
            assertTrue("The subsequent full blink must reveal the wordmark", ink > 500)
            revealed.recycle()
            brand.setActive(false)
        }
    }

    @Test fun hiddenWordmarkSurvivesRecreationAndPauseResume() = withActivity { activity, brand ->
        instrumentation.runOnMainSync {
            brand.restoreExpressionState(false, GhostExpression.BLINK)
            brand.setActive(false)
            brand.setActive(true)
            assertEquals(0f, currentFrame(brand).wordmarkAmount, 0f)
            activity.recreate()
        }
        instrumentation.waitForIdleSync()
        val deadline = System.currentTimeMillis() + 5_000
        var replacement: Activity? = null
        while (replacement == null && System.currentTimeMillis() < deadline) {
            instrumentation.runOnMainSync {
                replacement = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .firstOrNull { it is MainActivity && it !== activity }
            }
            Thread.sleep(50)
        }
        val recreated = requireNotNull(replacement) { "Activity did not recreate" }
        try {
            instrumentation.runOnMainSync {
                val restored = requireNotNull(findBrand(recreated.window.decorView))
                assertFalse(restored.expressionState.wordmarkVisible)
                assertEquals(GhostExpression.BLINK, restored.expressionState.previous)
                assertEquals(0f, currentFrame(restored).wordmarkAmount, 0f)
            }
        } finally {
            instrumentation.runOnMainSync { recreated.finish() }
        }
    }

    @Test fun blinksFullyCloseAndEitherWinkKeepsTheOtherEyePixelIdentical() {
        instrumentation.runOnMainSync {
            val brand = IndustrialBrandView(instrumentation.targetContext)
            brand.layout(0, 0, 400, 208)
            fun render(expression: GhostExpression?, left: Boolean, fraction: Float): Bitmap {
                brand.expressionState.finish()
                if (expression != null) brand.expressionState.start(expression, left)
                field("frame").set(brand, brand.expressionState.frame(fraction))
                return Bitmap.createBitmap(400, 208, Bitmap.Config.ARGB_8888).also { brand.draw(Canvas(it)) }
            }
            val neutral = render(null, true, 0f)
            fun assertClosed(image: Bitmap, fromX: Int, toX: Int) {
                for (x in fromX..toX) for (y in 54..106) {
                    val eyeCenter = if (fromX < 50) 26.46f else 71.82f
                    val dx = (x - eyeCenter) / 18.9f
                    val dy = (y - 79.62f) / 24.57f
                    if (dx * dx + dy * dy > 0.95f) continue
                    val pixel = image.getPixel(x, y)
                    val white = Color.red(pixel) > 180 && Color.green(pixel) > 180 && Color.blue(pixel) > 180
                    val blue = Color.blue(pixel) > 130 && Color.blue(pixel) > Color.red(pixel) * 1.25
                    assertFalse("Eye white or pupil remains at complete closure ($x,$y)", white || blue)
                }
            }
            for (expression in listOf(GhostExpression.BLINK, GhostExpression.DOUBLE_BLINK)) {
                val image = render(expression, true, if (expression == GhostExpression.BLINK) 0.5f else 0.25f)
                assertClosed(image, 7, 45)
                assertClosed(image, 53, 91)
                image.recycle()
            }
            for (left in listOf(true, false)) for (step in 0..20) {
                val image = render(GhostExpression.WINK, left, step / 20f)
                val stable = if (left) 52..93 else 6..47
                for (x in stable) for (y in 53..108) {
                    assertEquals("Wink moved or distorted the other eye", neutral.getPixel(x, y), image.getPixel(x, y))
                }
                if (step == 10) assertClosed(image, if (left) 7 else 53, if (left) 45 else 91)
                image.recycle()
            }
            neutral.recycle()
        }
    }

    private fun withActivity(test: (Activity, IndustrialBrandView) -> Unit) {
        val previous = shell("settings get global animator_duration_scale").trim()
        shell("settings put global animator_duration_scale 1")
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(
                instrumentation.targetContext.packageName, Manifest.permission.POST_NOTIFICATIONS
            )
        }
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        instrumentation.waitForIdleSync()
        val brand = findBrand(activity.window.decorView) ?: error("Industrial brand view missing")
        try {
            awaitOnMain(
                "The activity must be visible with native animations enabled",
                diagnostic = {
                    "attached=${brand.isAttachedToWindow}, shown=${brand.isShown}, " +
                        "windowVisibility=${brand.windowVisibility}, windowFocus=${activity.hasWindowFocus()}, " +
                        "active=${field("active").getBoolean(brand)}, " +
                        "animatorScale=${Settings.Global.getFloat(brand.context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, -1f)}, " +
                        "animatorsEnabled=${ValueAnimator.areAnimatorsEnabled()}"
                }
            ) {
                brand.isAttachedToWindow && brand.isShown && brand.windowVisibility == View.VISIBLE &&
                    field("active").getBoolean(brand) && ValueAnimator.areAnimatorsEnabled()
            }
            test(activity, brand)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            if (previous == "null") shell("settings delete global animator_duration_scale")
            else {
                require(previous.toFloatOrNull() != null)
                shell("settings put global animator_duration_scale $previous")
            }
        }
    }

    private fun awaitOnMain(message: String, diagnostic: (() -> String)? = null, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 3_000
        do {
            var satisfied = false
            instrumentation.runOnMainSync { satisfied = condition() }
            if (satisfied) return
            SystemClock.sleep(30)
        } while (SystemClock.uptimeMillis() < deadline)
        instrumentation.runOnMainSync {
            val details = diagnostic?.invoke()?.let { " ($it)" } ?: ""
            assertTrue("$message$details", condition())
        }
    }

    private fun findBrand(view: View): IndustrialBrandView? {
        if (view is IndustrialBrandView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findBrand(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun field(name: String) = IndustrialBrandView::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun currentFrame(brand: IndustrialBrandView) = field("frame").get(brand) as ExpressionFrame
    private fun animate(brand: IndustrialBrandView, expression: GhostExpression) =
        IndustrialBrandView::class.java.getDeclaredMethod("animateExpression", GhostExpression::class.java)
            .apply { isAccessible = true }.invoke(brand, expression)
    private fun callback(brand: IndustrialBrandView) = field("nextBlink").get(brand) as Runnable
    private fun shell(command: String) =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
}
