package xyz.robinroy.appblock.fixture

import android.app.Activity
import android.app.PictureInPictureParams
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Like YouTube's watch page: a video that plays as soon as it opens (silence,
 * through a media session, as YouTube's player does), says whether it's
 * playing, and shrinks into picture-in-picture when the user goes home
 * mid-video, but not once it's paused. A headset's pause key pauses it.
 */
class VideoActivity : Activity() {
    private lateinit var session: MediaSession
    private lateinit var track: AudioTrack
    private lateinit var state: TextView
    private var playing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state = TextView(this).apply { textSize = 24f }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            addView(View(context).apply { setBackgroundColor(Color.DKGRAY) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(state)
        })

        // Half a second of silence, looped: playing as far as Android can tell.
        val rate = 44_100
        val frames = rate / 2
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(frames * 2)
            .build()
        track.write(ShortArray(frames), 0, frames)
        track.setLoopPoints(0, frames, -1)

        session = MediaSession(this, "fixture video").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = play()
                override fun onPause() = pause()
            })
            isActive = true
        }
        play()
    }

    private fun play() {
        track.play()
        show(PlaybackState.STATE_PLAYING)
    }

    private fun pause() {
        track.pause()
        show(PlaybackState.STATE_PAUSED)
    }

    private fun show(playbackState: Int) {
        playing = playbackState == PlaybackState.STATE_PLAYING
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE)
            .setState(playbackState, 0, 1f)
            .build())
        state.text = if (playing) "Playing" else "Paused"
        // Android 12+: the system shrinks it on the way home, while it plays.
        if (Build.VERSION.SDK_INT >= 31) setPictureInPictureParams(pipParams().setAutoEnterEnabled(playing).build())
    }

    private fun pipParams() = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < 31 && playing) enterPictureInPictureMode(pipParams().build())
    }

    override fun onDestroy() {
        session.release()
        track.release()
        super.onDestroy()
    }
}
