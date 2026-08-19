package io.github.zqpvr.overdrive

import android.content.Context
import android.content.pm.PackageManager

/**
 * Detection of other applications that want the same effect module Overdrive does.
 *
 * AudioFlinger keeps one effect module per UUID within a chain and hands control to whichever
 * client holds the highest priority, so a system-wide equalizer sitting on session 0 is competing
 * for the exact DynamicsProcessing instance the limiter needs. Wavelet in legacy mode is the usual
 * case, JamesDSP and AudioFX behave the same way.
 *
 * When one of these is present the limiter is yielded by default on first run. The user can still
 * override that, and the detection is only ever used to pick a starting value.
 */
object Coexistence {

    /**
     * Every package here is also declared in the manifest queries element, without which
     * getPackageInfo returns NameNotFoundException on Android 11 and newer regardless of whether
     * the application is installed.
     */
    private val SYSTEM_WIDE_EQUALIZERS = listOf(
        "com.pittvandewitt.wavelet",
        "james.dsp",
        "org.lineageos.audiofx"
    )

    /** Package name of the first system-wide equalizer found, or null. */
    fun installedEqualizer(context: Context): String? {
        val packages = context.packageManager
        return SYSTEM_WIDE_EQUALIZERS.firstOrNull { name ->
            runCatching { packages.getPackageInfo(name, 0) }.isSuccess
        }
    }

    /** Short display name for the UI, falling back to the package name. */
    fun displayName(packageName: String): String = when (packageName) {
        "com.pittvandewitt.wavelet" -> "Wavelet"
        "james.dsp" -> "JamesDSP"
        "org.lineageos.audiofx" -> "AudioFX"
        else -> packageName
    }
}
