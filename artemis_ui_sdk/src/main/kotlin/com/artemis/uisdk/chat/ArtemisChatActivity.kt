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
        const val EXTRA_HANDLE_ID = "com.artemis.uisdk.HANDLE_ID"
        const val EXTRA_TITLE = "com.artemis.uisdk.TITLE"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val config = ArtemisUI.resolve(intent.getStringExtra(EXTRA_HANDLE_ID).orEmpty())?.config
        delegate.localNightMode = when (config?.theme?.mode) {
            "dark" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            "light" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        super.onCreate(savedInstanceState)
        val handleId = intent.getStringExtra(EXTRA_HANDLE_ID).orEmpty()
        if (ArtemisUI.resolve(handleId) == null) {
            finish()
            return
        }
        setContentView(R.layout.artemis_activity_chat)
        val container = findViewById<android.view.View>(R.id.artemis_chat_container)
        ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(container)
        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                replace(R.id.artemis_chat_container, ArtemisChatFragment.create(handleId, intent.getStringExtra(EXTRA_TITLE) ?: "Chat"))
            }
        }
    }

    internal fun closeChat() = finish()
}
