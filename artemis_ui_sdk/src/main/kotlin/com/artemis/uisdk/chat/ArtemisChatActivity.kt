package com.artemis.uisdk.chat

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commit
import com.artemis.uisdk.ArtemisUI
import com.artemis.uisdk.R
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

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
        val chatContainerView = findViewById<android.view.View>(R.id.artemis_chat_container)
        ViewCompat.setOnApplyWindowInsetsListener(chatContainerView) { view, windowInsets ->
            val systemWindowInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(systemWindowInsets.left, systemWindowInsets.top, systemWindowInsets.right, systemWindowInsets.bottom)
            windowInsets
        }
        ViewCompat.requestApplyInsets(chatContainerView)
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
