package io.github.zqpvr.overdrive

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BoostState.ensureInit(this)

        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Scaffold { padding ->
                        Screen(Modifier.padding(padding))
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The accessibility grant can change while we were away, which changes which host should
        // be holding the process.
        if (BoostState.enabled.value) {
            HostManager.ensureHost(this)
            BoostController.onHostStarted(this)
        }
        BoostController.sync()
    }

    fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Composable
private fun Screen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = context as MainActivity

    val enabled by BoostState.enabled.collectAsState()
    val gain by BoostState.gainDb.collectAsState()
    val onlyWhilePlaying by BoostState.onlyWhilePlaying.collectAsState()
    val manageSafeVolume by BoostState.manageSafeVolume.collectAsState()
    val yieldLimiter by BoostState.yieldLimiter.collectAsState()
    val status by BoostState.status.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Overdrive", style = MaterialTheme.typography.headlineMedium)

        // ---- boost ----
        Section {
            SwitchRow(
                title = "Boost",
                subtitle = statusLine(status, enabled),
                checked = enabled,
                onChange = { on ->
                    if (on && !HostManager.accessibilityGranted(context)) {
                        activity.askForNotificationPermission()
                    }
                    BoostController.setEnabled(context, on)
                }
            )

            Spacer(Modifier.height(8.dp))

            Text("Gain  +%.1f dB".format(gain), style = MaterialTheme.typography.titleMedium)
            Slider(
                value = gain,
                onValueChange = { BoostController.setGain(context, it) },
                valueRange = BoostState.MIN_GAIN_DB..BoostState.MAX_GAIN_DB,
                steps = 39
            )
            Hint(
                "Past roughly +10 dB whatever is catching peaks is working most of the time and " +
                    "the mix starts to sound flat and loud rather than louder. See the limiter " +
                    "section below for what is actually catching them right now."
            )

            Spacer(Modifier.height(8.dp))

            SwitchRow(
                title = "Attach only while playing",
                subtitle = "Releases the effect ~12 s after playback stops. Costs a few " +
                    "milliseconds when the next track starts.",
                checked = onlyWhilePlaying,
                onChange = {
                    BoostState.setOnlyWhilePlaying(it)
                    BoostController.sync()
                }
            )
        }

        // ---- limiter and coexistence ----
        Section {
            Text("Limiter", style = MaterialTheme.typography.titleMedium)

            val equalizer = remember { Coexistence.installedEqualizer(context) }
            val equalizerName = equalizer?.let { Coexistence.displayName(it) }

            Hint(limiterLine(status.limiter, equalizerName))

            Spacer(Modifier.height(8.dp))

            SwitchRow(
                title = "Leave the limiter to ${equalizerName ?: "another app"}",
                subtitle = "Android keeps one DynamicsProcessing instance per audio session and " +
                    "gives control to a single client, so claiming it stops a system-wide " +
                    "equalizer on session 0 from working. Overdrive already asks for the lowest " +
                    "priority; this skips the attempt entirely.",
                checked = yieldLimiter,
                onChange = { BoostController.setYieldLimiter(context, it) }
            )

            if (equalizerName != null) {
                Spacer(Modifier.height(8.dp))
                Hint(
                    "$equalizerName was detected, so this defaulted on. Its per-session mode does " +
                        "not collide and does not need this; legacy mode does. Note that " +
                        "LoudnessEnhancer still applies its own dynamic range compression to " +
                        "anything pushed past full scale, so yielding is not the same as running " +
                        "with no protection at all. Keep the gain lower than you would alone, " +
                        "because an EQ curve with positive bands has already spent some headroom."
                )
            }
        }

        // ---- host ----
        Section {
            Text("Keep-alive", style = MaterialTheme.typography.titleMedium)

            val granted = HostManager.accessibilityGranted(context)
            Hint(
                "AudioFlinger destroys the effect the moment this app's process dies, so " +
                    "something has to hold the process. Neither option below uses measurable " +
                    "battery; they differ in what they cost you on screen."
            )

            Spacer(Modifier.height(8.dp))

            Text(
                if (granted) "Silent mode, no notification" else "Notification mode",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Hint(
                if (granted) {
                    "The accessibility lifeline is on. It receives no events and cannot read " +
                        "the screen; it exists purely so the system keeps this process alive."
                } else {
                    "Currently held by a foreground service with a minimum-priority " +
                        "notification. Grant the accessibility lifeline to drop the notification."
                }
            )

            TextButton(onClick = { HostManager.openAccessibilitySettings(context) }) {
                Text(if (granted) "Accessibility settings" else "Grant accessibility lifeline")
            }

            if (!granted) {
                Hint(
                    "On GrapheneOS a sideloaded app's accessibility toggle is greyed out until " +
                        "you allow it: App info, overflow menu, Allow restricted settings."
                )
            }
        }

        // ---- safe volume ----
        Section {
            Text("Headphone limit", style = MaterialTheme.typography.titleMedium)

            val hasPermission = SafeVolume.permissionGranted(context)
            val lifted = SafeVolume.isLifted(context)

            Hint(
                "Separate from the boost and free: Android clamps headphone output for EN 50332 " +
                    "compliance. Clearing that flag needs no running process at all."
            )

            Spacer(Modifier.height(8.dp))

            if (hasPermission) {
                SwitchRow(
                    title = if (lifted) "Limit lifted" else "Lift the limit",
                    subtitle = "Re-applied every 6 hours and after boot, on an inexact alarm " +
                        "that never wakes the device. The system re-arms it on its own.",
                    checked = manageSafeVolume,
                    onChange = { on ->
                        BoostState.setManageSafeVolume(on)
                        if (on) {
                            SafeVolume.lift(context)
                            SafeVolumeScheduler.schedule(context)
                        } else {
                            SafeVolumeScheduler.cancel(context)
                        }
                    }
                )
            } else {
                Hint("Run this once over adb, then reopen this screen:")
                Spacer(Modifier.height(6.dp))
                Mono(SafeVolume.grantCommand(context))
                Spacer(Modifier.height(6.dp))
                Button(onClick = {
                    val command = SafeVolume.grantCommand(context)
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("adb", command))
                }) {
                    Text("Copy command")
                }
            }

            Hint(
                "Android 14 added a second limiter on top of this one: computed sound dose, " +
                    "tracked inside audioserver over a rolling seven days. It is not reachable " +
                    "from an app, and in some regions it cannot be turned off at all. Check " +
                    "Settings, Sound and vibration, Hearing wellness."
            )
        }

        status.error?.let { error ->
            Section {
                Text("Last engine error", style = MaterialTheme.typography.titleMedium)
                Mono(error)
                Hint(
                    "A failure here usually means another client holds the global effect slot, " +
                        "or this device's audio HAL ships no software effect bundle."
                )
            }
        }

        Hint(
            "Sustained boost into headphones damages hearing, and into a phone speaker it will " +
                "drive the amp into its excursion limit. The limiter protects the signal, not " +
                "your ears and not the driver."
        )
    }
}

@Composable
private fun Section(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Hint(subtitle)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun Mono(text: String) {
    Text(
        text,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private fun limiterLine(state: BoostEngine.LimiterState, equalizer: String?): String = when (state) {
    BoostEngine.LimiterState.NONE -> "Not attached"
    BoostEngine.LimiterState.ACTIVE -> "Brickwall limiter active at -0.5 dBFS"
    BoostEngine.LimiterState.YIELDED_BY_CHOICE ->
        "Skipped by choice. LoudnessEnhancer compression only."
    BoostEngine.LimiterState.YIELDED_TO_OTHER ->
        "Claimed by ${equalizer ?: "another app"}. LoudnessEnhancer compression only."
    BoostEngine.LimiterState.UNAVAILABLE ->
        "Unavailable on this device. LoudnessEnhancer compression only."
}

private fun statusLine(status: BoostState.Status, enabled: Boolean): String = when {
    !enabled -> "Off"
    status.attached -> "Attached to the global output mix"
    status.host == HostManager.Host.NONE -> "No host is holding the process"
    else -> "Armed, waiting for playback"
}
