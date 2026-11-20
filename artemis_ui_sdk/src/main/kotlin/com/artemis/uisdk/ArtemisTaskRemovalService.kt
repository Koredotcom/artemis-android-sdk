package com.artemis.uisdk

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/** Keeps the SDK able to end active Runtime sessions when Android removes the app task. */
internal class ArtemisTaskRemovalService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var latestStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (BuildConfig.DEBUG) Log.d(TAG, "App task removed; ending active Runtime sessions")
        ArtemisUI.endActiveSessionsAfterTaskRemoval()
        val startId = latestStartId
        mainHandler.postDelayed({ stopSelfResult(startId) }, DRAIN_DELAY_MS)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ArtemisTaskRemoval"
        private const val DRAIN_DELAY_MS = 3_500L

        fun start(context: Context) {
            val appContext = context.applicationContext
            appContext.startService(Intent(appContext, ArtemisTaskRemovalService::class.java))
        }
    }
}
