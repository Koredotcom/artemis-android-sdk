package com.artemis.uisdk.chat

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commit
import com.artemis.uisdk.ArtemisUI
import com.artemis.uisdk.R

class ArtemisChatActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_HANDLE_IDENTIFIER = "com.artemis.uisdk.HANDLE_ID"
        const val EXTRA_TITLE = "com.artemis.uisdk.TITLE"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val uiConfiguration = ArtemisUI.resolveHandle(intent.getStringExtra(EXTRA_HANDLE_IDENTIFIER).orEmpty())?.config
        delegate.localNightMode = when (uiConfiguration?.theme?.mode) {
            "dark" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            "light" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        super.onCreate(savedInstanceState)
        val handleIdentifier = intent.getStringExtra(EXTRA_HANDLE_IDENTIFIER).orEmpty()
        if (ArtemisUI.resolveHandle(handleIdentifier) == null) {
            finish()
            return
        }
        setContentView(R.layout.artemis_activity_chat)
        // Edge-to-edge on every API level, as Android 15+ enforces: ArtemisChatFragment pads its
        // header for the status bar and its root for the navigation bar and keyboard.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        run {
            // Android 14 and below still paint these colors over the content; keep them clear.
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            // Dark navigation icons need API 26; below that, a light scrim keeps white icons readable.
            window.navigationBarColor = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                android.graphics.Color.TRANSPARENT else 0x66000000
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            // No system scrim over the header color or the chat background.
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                replace(
                    R.id.artemis_chat_container,
                    ArtemisChatFragment.create(handleIdentifier, intent.getStringExtra(EXTRA_TITLE) ?: "Chat"),
                )
            }
        }
    }

    internal fun closeChat() = finish()
}
