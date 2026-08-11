package com.example.wavesync.core.player

import android.content.Intent
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Background audio playback for WaveSync.
 *
 * ## How this service relates to the Android OS
 *
 * A [MediaSessionService] is a *bound + started* service with a very specific contract:
 *
 *  - **Binding.** When any process builds a `MediaController` pointed at this service, the OS binds
 *    here and calls [onGetSession]. That controller may be our own UI, or it may be SystemUI,
 *    Android Auto, Wear, or Assistant. We do not manage those connections; we only hand back the
 *    session.
 *  - **The session is the OS-facing handle.** [MediaSession] registers a token with the platform's
 *    `MediaSessionManager`. That token is what makes hardware media buttons, Bluetooth AVRCP
 *    commands, the lock-screen transport controls, and the Quick Settings media chip work. None of
 *    that involves this class directly -- they all talk to the session.
 *  - **Foreground promotion is automatic.** [MediaSessionService] observes the session's player and
 *    calls `startForeground()` with a media-style notification the moment playback becomes active,
 *    then `stopForeground(STOP_FOREGROUND_DETACH)` when it stops. We never build a Notification and
 *    never call startForeground ourselves. That is also why a *paused* media notification can be
 *    swiped away but a *playing* one cannot.
 *  - **Lifetime.** While in the foreground the OS will not kill us for memory pressure. Once
 *    playback stops we are demoted to an ordinary service and become killable at any time -- which
 *    is exactly why [onDestroy] must release everything deterministically rather than trusting GC.
 */
// ExoPlayer and DefaultMediaNotificationProvider are marked @UnstableApi. That marker is enforced
// by Android Lint (UnsafeOptInUsageError), not by Kotlin's @RequiresOptIn, so androidx.annotation's
// OptIn is what silences it -- a `kotlin { compilerOptions { optIn(...) } }` entry does nothing
// here beyond emitting "is not an opt-in requirement marker".
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    /**
     * Nullable + cleared in [onDestroy] on purpose.
     *
     * The Service object itself is retained by the system's ActivityThread for as long as the
     * service is alive, so this field is not the leak. The leak is what an *unreleased* session
     * and player transitively pin: renderers, the internal playback HandlerThread, the loaded
     * MediaSource graph, and any DataSource caches. Nulling after release also makes
     * use-after-release impossible, which otherwise throws or silently no-ops.
     */
    private var mediaSession: MediaSession? = null

    /**
     * Observes focus-driven state changes. This listener is strictly read-only with respect to
     * audio focus -- see the note in [onCreate] about never running two focus state machines.
     */
    private val playerListener = object : Player.Listener {

        /**
         * Fires when playback is *suppressed*: the player still wants to play
         * (`playWhenReady == true`) but is producing no sound.
         *
         * This -- not [onPlayWhenReadyChanged] -- is where transient audio focus loss surfaces, and
         * therefore where an incoming phone call shows up. There is no
         * `PLAY_WHEN_READY_CHANGE_REASON_..._TRANSIENT` constant; transient loss never touches
         * playWhenReady at all. Getting this wrong is the classic Media3 mistake: code that
         * watches only playWhenReady sees nothing during a phone call and reports "still playing".
         */
        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
            when (playbackSuppressionReason) {
                Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS ->
                    // Phone call started, a navigation prompt spoke, another app took focus
                    // briefly. ExoPlayer has already paused output and will resume by itself on
                    // AUDIOFOCUS_GAIN. Do NOT call play() here -- that fights the focus manager.
                    Log.d(TAG, "Playback suppressed: transient audio focus loss (auto-resumes)")

                Player.PLAYBACK_SUPPRESSION_REASON_NONE ->
                    // Focus regained; output resumes on its own.
                    Log.d(TAG, "Playback suppression lifted")

                // Remaining reasons (unsuitable audio output, scrubbing) are not focus-related
                // and need no handling here.
                else -> Unit
            }
        }

        /**
         * Fires when the intent to play actually changes. Focus-wise this is the *permanent* case.
         */
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            when (reason) {
                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS ->
                    // Another app took focus for good (AUDIOFOCUS_LOSS). ExoPlayer has abandoned
                    // focus and will NOT auto-resume. Resuming is now a user decision.
                    Log.d(TAG, "Paused: permanent audio focus loss")

                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY ->
                    // Headphones unplugged or Bluetooth disconnected. Handled for us because we
                    // set setHandleAudioBecomingNoisy(true); without it, audio would suddenly
                    // blast out of the phone speaker.
                    Log.d(TAG, "Paused: audio became noisy (output route lost)")

                Player.PLAY_WHEN_READY_CHANGE_REASON_SUPPRESSED_TOO_LONG ->
                    // A "transient" loss outlived ExoPlayer's internal timeout, so it was
                    // converted into a real pause. The automatic resume is forfeited -- transient
                    // does not mean unbounded.
                    Log.d(TAG, "Paused: suppression lasted too long, auto-resume forfeited")

                // USER_REQUEST, REMOTE and END_OF_MEDIA_ITEM are ordinary transport changes, not
                // interruptions, so nothing to observe.
                else -> Unit
            }
        }
    }

    /**
     * Recovery hook for API 31+, where starting a foreground service from the background throws
     * ForegroundServiceStartNotAllowedException. Media3 catches it and routes it here instead of
     * letting it crash the process. Typical trigger: a Bluetooth button or widget asks for
     * playback while the app has no visible UI.
     */
    private val serviceListener = object : MediaSessionService.Listener {
        override fun onForegroundServiceStartNotAllowedException() {
            // Correct recovery is an ordinary (non-foreground) notification asking the user to
            // open the app, since we are not allowed to start playing on our own here.
            Log.w(TAG, "Cannot start foreground service from background; playback not started")
        }
    }

    override fun onCreate() {
        // super.onCreate() FIRST: MediaSessionService wires up its internal session manager and
        // notification plumbing here. Touching setMediaNotificationProvider or setListener before
        // this point operates on uninitialised state.
        super.onCreate()

        // USAGE_MEDIA + CONTENT_TYPE_MUSIC is not cosmetic -- it drives the entire focus policy:
        //
        //  * setAudioAttributes(attrs, handleAudioFocus = true) REQUIRES usage to be USAGE_MEDIA
        //    or USAGE_GAME. Anything else throws IllegalArgumentException at build time.
        //
        //  * contentType decides duck-vs-pause. With AUDIO_CONTENT_TYPE_MUSIC, an
        //    AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK (a navigation prompt, a notification chime) lowers
        //    our volume by an internal 0.2f multiplier and keeps playing. With
        //    AUDIO_CONTENT_TYPE_SPEECH it would pause instead -- which is what you want for
        //    podcasts and audiobooks, where talking over speech is useless. WaveSync is a music
        //    app, so ducking is correct.
        //
        //    Note ducking is effectively invisible to us: it produces no listener callback, and
        //    player.volume still reports the un-ducked value because the multiplier is applied
        //    downstream. Do not write UI that reads back volume during a duck and "corrects" it.
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val player = ExoPlayer.Builder(applicationContext)
            // handleAudioFocus = true hands the ENTIRE focus state machine to Media3:
            //   AUDIOFOCUS_LOSS_TRANSIENT           -> suppress output, auto-resume on GAIN
            //   AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK  -> duck to 0.2f volume, keep playing
            //   AUDIOFOCUS_LOSS                     -> pause for good, abandon focus, no resume
            //
            // CRITICAL: because of this, the app must NEVER call AudioManager.requestAudioFocus
            // itself. The platform tracks focus per listener instance, so a second requester in
            // the same process means: our manual request keeps focus alive after ExoPlayer
            // abandons it (the app we interrupted never gets AUDIOFOCUS_GAIN and stays silent
            // forever); and both sides fight over resume, producing pause/resume stutter around
            // phone calls. Pick one owner. This is that owner.
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            // Pause instead of switching to the phone speaker when headphones/Bluetooth drop.
            .setHandleAudioBecomingNoisy(true)
            // Holds a PowerManager WakeLock + WifiLock, but only while actually playing. Use
            // WAKE_MODE_LOCAL instead if playback is ever purely local files.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            // applicationContext, NOT `this`: the builder retains the Context it is given, and a
            // Service context retained past onDestroy is a leaked Service + its ContextImpl.
            .build()

        player.addListener(playerListener)

        mediaSession = MediaSession.Builder(this, player)
            // Makes the notification tappable. Built from the launcher intent rather than a direct
            // MainActivity reference so :core:player never depends on :app -- see
            // SessionActivityIntent.kt. Null-safe: a session with no session activity is legal,
            // the notification simply is not tappable.
            .apply { buildSessionActivityIntent()?.let(::setSessionActivity) }
            .build()

        // Optional: name the notification channel. The channel itself is created for us; without
        // this it gets a generic default label in system notification settings.
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelName(R.string.playback_notification_channel_name)
                .build()
        )

        setListener(serviceListener)
    }

    /**
     * Called when a `MediaController` binds. Returning the session accepts the connection;
     * returning null rejects it outright.
     *
     * This is not the place for per-caller access control -- it has no way to accept one caller
     * and refuse another usefully. Vetting belongs in `MediaSession.Callback.onConnect`, which
     * receives the [MediaSession.ControllerInfo] and can grant a reduced command set.
     */
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    /**
     * The user swiped WaveSync off the Recents screen.
     *
     * The OS does *not* automatically kill a started foreground service here, so the policy is
     * ours to choose. Keeping playback alive while the task is gone is the entire point of a
     * background media service -- a user who swipes away the UI mid-song expects the song to keep
     * going, with the notification as the remaining control surface.
     *
     * Media3 also offers `pauseAllPlayersAndStopSelf()`, but that always stops on swipe, which is
     * the behaviour users complain about. Stop only when nothing is actually playing, otherwise
     * we would leave an idle service (and a stale notification) hanging around.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    /**
     * Deterministic teardown. Order matters, and the failure modes are not subtle.
     *
     * **Player before session.** Releasing the session first unregisters it from the platform
     * while the notification manager and any in-flight controller commands may still dispatch onto
     * a live player -- those then land on a session that no longer exists. Releasing the player
     * first puts it in a terminal state whose final events propagate through a still-valid
     * session, which then shuts down cleanly.
     *
     * **Why releasing the session is non-negotiable.** Sessions live in a process-global registry
     * so media buttons can be routed to them, which means an unreleased session outlives this
     * service and leaks for the life of the *process*. Two symptoms follow: the platform logs
     * "MediaSession was not released" and a zombie entry lingers in the Quick Settings media
     * controls; and because the session id defaults to the empty string, the next
     * `MediaSession.Builder(...).build()` collides with the old one and throws
     * IllegalStateException -- i.e. restarting playback crashes, which is usually how this bug
     * gets discovered.
     *
     * **removeListener** is strictly redundant today, since releasing the player drops its
     * listeners anyway and the player's lifetime equals this service's. It is kept because
     * `playerListener` is an anonymous object capturing `PlaybackService.this`: the day the player
     * is hoisted into a longer-lived scope (a DI singleton, say), that capture becomes a retained
     * destroyed Service. One line, and it fails safe.
     */
    override fun onDestroy() {
        // Drop our reference from the base class first; serviceListener is an anonymous object
        // capturing this Service, and setListener takes a non-null argument, so clearListener()
        // is the supported way to detach it.
        clearListener()
        mediaSession?.run {
            player.removeListener(playerListener)
            player.release()
            release()
        }
        mediaSession = null

        // super.onDestroy() LAST: the base class tears down its notification manager and unbinds
        // its internal session bookkeeping, which must still see a consistent world above.
        super.onDestroy()
    }

    private companion object {
        const val TAG = "PlaybackService"
    }
}
