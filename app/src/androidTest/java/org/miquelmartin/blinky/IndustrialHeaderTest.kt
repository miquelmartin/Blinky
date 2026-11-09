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
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
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
        val deadline = System.currentTimeMillis() + 3_000
        while (!brand.handler.hasCallbacks(callback(brand)) && System.currentTimeMillis() < deadline) Thread.sleep(50)
        instrumentation.runOnMainSync {
            assertTrue(
                "Visible=${brand.isShown}, attached=${brand.isAttachedToWindow}, " +
                    "active=${field("active").getBoolean(brand)}, animators=${ValueAnimator.areAnimatorsEnabled()}",
                brand.handler.hasCallbacks(callback(brand))
            )
        }
        instrumentation.runOnMainSync { activity.moveTaskToBack(true) }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertFalse(brand.handler.hasCallbacks(callback(brand)))
            assertEquals(null, field("animator").get(brand))
            assertEquals(0f, field("closure").getFloat(brand), 0f)
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun repeatedVisibleCallbacksDoNotPostponeTheBlink() = withActivity { _, brand ->
        val deadline = System.currentTimeMillis() + 13_500
        repeat(12) {
            instrumentation.runOnMainSync { brand.onVisibilityAggregated(true) }
            Thread.sleep(500)
        }
        var closed = false
        while (!closed && System.currentTimeMillis() < deadline) {
            instrumentation.runOnMainSync { closed = field("closure").getFloat(brand) > 0.1f }
            Thread.sleep(30)
        }
        assertTrue("The randomized blink never started", closed)
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun removingAnimationsCancelsBlinkAndRestoresOpenWhileVisible() = withActivity { _, brand ->
        shell("settings put global animator_duration_scale 0")
        val deadline = System.currentTimeMillis() + 3_000
        while (brand.handler.hasCallbacks(callback(brand)) && System.currentTimeMillis() < deadline) Thread.sleep(50)
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertEquals(0f, Settings.Global.getFloat(brand.context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE), 0f)
            assertFalse(brand.handler.hasCallbacks(callback(brand)))
            assertEquals(null, field("animator").get(brand))
            assertEquals(0f, field("closure").getFloat(brand), 0f)
        }
        shell("settings put global animator_duration_scale 1")
        val enabledDeadline = System.currentTimeMillis() + 3_000
        while (!brand.handler.hasCallbacks(callback(brand)) && System.currentTimeMillis() < enabledDeadline) Thread.sleep(50)
        instrumentation.runOnMainSync { assertTrue(brand.handler.hasCallbacks(callback(brand))) }
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

    private fun findBrand(view: View): IndustrialBrandView? {
        if (view is IndustrialBrandView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findBrand(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun field(name: String) = IndustrialBrandView::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun callback(brand: IndustrialBrandView) = field("nextBlink").get(brand) as Runnable
    private fun shell(command: String) =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
}
