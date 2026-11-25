package com.artemis.uisdk.chat

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.artemis.uisdk.R

/** What the controls drive: a [android.widget.VideoView] or a prepared [android.media.MediaPlayer]. */
internal interface TemplatePlayback {
    val isPlaying: Boolean
    val currentPosition: Int
    val duration: Int
    fun start()
    fun pause()
    fun seekTo(positionMs: Int)
    fun setVolume(volume: Float)
}

/**
 * Web-style inline media controls (play/pause, "0:00 / 0:05", seek bar, mute) shared by the
 * video and audio templates. The owner supplies a [TemplatePlayback] once prepared and an [onPlay]
 * hook used when nothing is prepared yet (first tap, or after the row was recycled).
 */
@SuppressLint("ViewConstructor")
internal class TemplateMediaControls(
    context: Context,
    private val tint: Int,
    trackColor: Int,
    private val playLabel: Int,
    private val onPlay: () -> Unit,
) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()

    private var player: TemplatePlayback? = null
    private var muted = false
    private var knownDurationMs = 0
    private var userSeeking = false

    private val playButton = ImageView(context).apply {
        setImageResource(R.drawable.artemis_ic_media_play)
        imageTintList = ColorStateList.valueOf(tint)
        contentDescription = context.getString(playLabel)
        setPadding(dp(4), dp(4), dp(4), dp(4))
        setOnClickListener { togglePlay() }
    }
    private val time = TextView(context).apply {
        textSize = 13f
        setTextColor(tint)
        text = format(0, 0)
    }
    val seekBar = SeekBar(context).apply {
        max = 1000
        progressTintList = ColorStateList.valueOf(tint)
        // Web's native players show a plain progress line with no drag handle.
        thumb = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        splitTrack = false
        progressBackgroundTintList = ColorStateList.valueOf(trackColor)
        setPadding(dp(8), 0, dp(8), 0)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) time.text = format(knownDurationMs * progress / 1000, knownDurationMs)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) { userSeeking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                userSeeking = false
                player?.let { runCatching { it.seekTo(knownDurationMs * seekBar.progress / 1000) } }
                refresh()
            }
        })
    }
    private val volumeButton = ImageView(context).apply {
        setImageResource(R.drawable.artemis_ic_media_volume_up)
        imageTintList = ColorStateList.valueOf(tint)
        contentDescription = context.getString(R.string.artemis_media_mute)
        setPadding(dp(4), dp(4), dp(4), dp(4))
        setOnClickListener {
            muted = !muted
            applyVolume()
            setImageResource(if (muted) R.drawable.artemis_ic_media_volume_off else R.drawable.artemis_ic_media_volume_up)
            contentDescription = context.getString(if (muted) R.string.artemis_media_unmute else R.string.artemis_media_mute)
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            if (player?.let { runCatching { it.isPlaying }.getOrDefault(false) } == true) postDelayed(this, 250)
        }
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(playButton, LayoutParams(dp(32), dp(32)))
        addView(time, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
    }

    /** Puts the seek bar in this row (audio) instead of on its own row (video). */
    fun inlineSeekBar() {
        addView(seekBar, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        addView(volumeButton, LayoutParams(dp(32), dp(32)))
    }

    /** Video layout: the volume button sits at the row end and the seek bar goes on a row of its own. */
    fun trailingVolumeOnly() {
        addView(android.view.View(context), LayoutParams(0, 1, 1f))
        addView(volumeButton, LayoutParams(dp(32), dp(32)))
    }

    fun setDuration(durationMs: Int) {
        if (durationMs > 0) knownDurationMs = durationMs
        refresh()
    }

    fun attach(playback: TemplatePlayback) {
        player = playback
        runCatching { playback.duration }.getOrNull()?.takeIf { it > 0 }?.let { knownDurationMs = it }
        applyVolume()
        refresh()
    }

    /** The player was released (row recycled or another media item started). */
    fun detach() {
        removeCallbacks(ticker)
        player = null
        seekBar.progress = 0
        time.text = format(0, knownDurationMs)
        showPlaying(false)
    }

    fun onStarted() { showPlaying(true); removeCallbacks(ticker); post(ticker) }

    fun onPaused() { showPlaying(false); refresh() }

    fun onCompleted() {
        showPlaying(false)
        seekBar.progress = 0
        time.text = format(0, knownDurationMs)
    }

    private fun togglePlay() {
        val current = player
        if (current == null) { onPlay(); return }
        runCatching {
            if (current.isPlaying) { current.pause(); onPaused() } else { current.start(); onStarted() }
        }
    }

    private fun applyVolume() {
        player?.let { runCatching { it.setVolume(if (muted) 0f else 1f) } }
    }

    private fun showPlaying(playing: Boolean) {
        playButton.setImageResource(if (playing) R.drawable.artemis_ic_media_pause else R.drawable.artemis_ic_media_play)
        playButton.contentDescription = context.getString(if (playing) R.string.artemis_media_pause else playLabel)
    }

    private fun refresh() {
        if (userSeeking) return
        val position = player?.let { runCatching { it.currentPosition }.getOrNull() } ?: 0
        time.text = format(position, knownDurationMs)
        seekBar.progress = if (knownDurationMs > 0) (position.toLong() * 1000 / knownDurationMs).toInt() else 0
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    private fun format(positionMs: Int, durationMs: Int): String {
        fun clock(ms: Int): String {
            val total = (ms / 1000).coerceAtLeast(0)
            return if (total >= 3600) "%d:%02d:%02d".format(total / 3600, total / 60 % 60, total % 60)
            else "%d:%02d".format(total / 60, total % 60)
        }
        return if (durationMs > 0) "${clock(positionMs)} / ${clock(durationMs)}" else clock(positionMs)
    }
}
