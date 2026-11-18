package com.artemis.uisdk.chat

import android.view.View
import android.view.ViewGroup
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import android.widget.TextView
import java.net.URI
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class CallUiParityTest {
    @Test fun mediaPermissionRequiresExactOrigin() {
        val expected = URI("https://runtime.example.com/chat")
        assertTrue(CallOriginPolicy.sameOrigin(expected, "https://runtime.example.com"))
        assertTrue(CallOriginPolicy.sameOrigin(expected, "https://RUNTIME.example.com:443"))
        assertFalse(CallOriginPolicy.sameOrigin(expected, "https://runtime.example.com:8443"))
        assertFalse(CallOriginPolicy.sameOrigin(expected, "https://runtime.example.com.attacker.test"))
        assertFalse(CallOriginPolicy.sameOrigin(expected, "http://runtime.example.com"))
        assertFalse(CallOriginPolicy.sameOrigin(expected, "https://user@runtime.example.com"))
        assertFalse(CallOriginPolicy.sameOrigin(expected, null))
    }

    @Test fun callControlsWrapWithoutClippingOnNarrowWidths() {
        val context = RuntimeEnvironment.getApplication()
        val layout = CallControlsFlowLayout(context)
        repeat(4) { index ->
            layout.addView(TextView(context).apply { text = "Control $index"; minWidth = 130; minHeight = 48 },
                ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        layout.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        layout.layout(0, 0, layout.measuredWidth, layout.measuredHeight)
        assertEquals(layout.getChildAt(0).top, layout.getChildAt(1).top)
        assertTrue(layout.getChildAt(2).top >= layout.getChildAt(0).bottom)
        assertTrue((0 until layout.childCount).all { layout.getChildAt(it).left >= 0 && layout.getChildAt(it).right <= 300 })
    }

    @Test fun deniedMicrophonePermissionLeavesAnActionableErrorScreen() {
        val context = RuntimeEnvironment.getApplication()
        val intent = Intent(context, ArtemisContactCenterCallActivity::class.java).apply {
            putExtra(ArtemisContactCenterCallActivity.EXTRA_RUNTIME_ENDPOINT, "https://runtime.example.com")
            putExtra(ArtemisContactCenterCallActivity.EXTRA_ENDPOINT_JSON, """{"callId":"c","mediaSessionId":"m","callType":"audio","addresses":[],"iceServers":[]}""")
        }
        val controller = Robolectric.buildActivity(ArtemisContactCenterCallActivity::class.java, intent).setup()
        val activity = controller.get()
        activity.onRequestPermissionsResult(7412, arrayOf(android.Manifest.permission.RECORD_AUDIO), intArrayOf(PackageManager.PERMISSION_DENIED))
        val all = mutableListOf<TextView>()
        fun collect(view: View) {
            if (view is TextView) all.add(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index))
        }
        collect(activity.window.decorView)
        assertFalse(activity.isFinishing)
        assertTrue(all.any { it.visibility == View.VISIBLE && it.text.toString().contains("Microphone permission") })
        assertTrue(all.any { it.visibility == View.VISIBLE && it.text == "Hang up" })
        controller.pause().stop().destroy()
    }

    @Test fun queuedBridgeMessagesCannotReviveLocallyEndedCall() {
        val context = RuntimeEnvironment.getApplication()
        val intent = Intent(context, ArtemisContactCenterCallActivity::class.java).apply {
            putExtra(ArtemisContactCenterCallActivity.EXTRA_RUNTIME_ENDPOINT, "https://runtime.example.com")
            putExtra(ArtemisContactCenterCallActivity.EXTRA_ENDPOINT_JSON, """{"callId":"c","mediaSessionId":"m","callType":"audio","addresses":[],"iceServers":[]}""")
        }
        val controller = Robolectric.buildActivity(ArtemisContactCenterCallActivity::class.java, intent).setup()
        val activity = controller.get()
        val statusField = ArtemisContactCenterCallActivity::class.java.getDeclaredField("statusView").apply { isAccessible = true }
        val status = statusField.get(activity) as TextView
        val before = status.text.toString()
        activity.onBackPressed()
        val bridgeType = Class.forName("com.artemis.uisdk.chat.ArtemisContactCenterCallActivity\$Bridge")
        val constructor = bridgeType.getDeclaredConstructor(ArtemisContactCenterCallActivity::class.java).apply { isAccessible = true }
        val bridge = constructor.newInstance(activity)
        val post = bridgeType.getDeclaredMethod("postMessage", String::class.java).apply { isAccessible = true }
        post.invoke(bridge, """{"type":"connected"}""")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(before, status.text.toString())
        controller.pause().stop().destroy()
    }
}
