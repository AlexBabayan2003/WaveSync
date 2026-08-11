package com.example.wavesync.core.player

import android.app.PendingIntent
import android.app.Service
import android.content.Intent

/**
 * Builds the [PendingIntent] the system fires when the user taps the media notification or the
 * lock-screen controls.
 *
 * The obvious implementation -- `Intent(this, MainActivity::class.java)` -- would make
 * `:core:player` depend on `:app`, inverting the module graph and creating a cycle the moment
 * `:app` depends back on this module. Resolving the launcher activity at runtime keeps the
 * dependency direction intact with zero compile-time coupling and no configuration: `:app` already
 * declares a MAIN/LAUNCHER activity, and the OS tells us which one it is.
 *
 * Returns null when the package has no launcher activity, which is legal --
 * `MediaSession.Builder.setSessionActivity` is simply skipped and the notification is not tappable.
 */
internal fun Service.buildSessionActivityIntent(): PendingIntent? {
    val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return null

    // Reuse the existing task instead of stacking a second copy of the activity on top of it.
    launchIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)

    return PendingIntent.getActivity(
        this,
        /* requestCode = */ 0,
        launchIntent,
        // FLAG_IMMUTABLE is mandatory on API 31+ (one of IMMUTABLE/MUTABLE must be specified) and
        // available since API 23, so with minSdk 24 no version guard is needed. Immutable is the
        // right choice: the recipient is SystemUI, which has no business filling in extras.
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
