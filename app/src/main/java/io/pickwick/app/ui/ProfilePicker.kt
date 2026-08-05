package io.pickwick.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import io.pickwick.app.data.Profile
import io.pickwick.app.data.isValidDirectionPin

/**
 * The kid's face of a profile: a colored circle with a bundled Fluent Emoji
 * image when the APK carries one, else the emoji itself rendered large. The
 * lookup-by-name fallback means a missing asset degrades to text, never a hole.
 */
@Composable
fun ProfileAvatar(profile: Profile, size: Int, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val resId = remember(profile.avatar) {
        val name = FLUENT_AVATARS[profile.avatar]
        if (name == null) 0
        else context.resources.getIdentifier(name, "drawable", context.packageName)
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(Color(profile.colorArgb))
    ) {
        if (resId != 0) {
            Image(
                painter = androidx.compose.ui.res.painterResource(resId),
                contentDescription = profile.name,
                modifier = Modifier.size((size * 0.68f).dp)
            )
        } else {
            Text(
                profile.avatar,
                fontSize = TextUnit(size * 0.5f, TextUnitType.Sp)
            )
        }
    }
}

/** Emoji → bundled Fluent Emoji 3D drawable (res/drawable-nodpi/avatar_*.png). */
val FLUENT_AVATARS: Map<String, String> = mapOf(
    "🦊" to "avatar_fox", "🐼" to "avatar_panda", "🦁" to "avatar_lion",
    "🐸" to "avatar_frog", "🐰" to "avatar_rabbit", "🦄" to "avatar_unicorn",
    "🐙" to "avatar_octopus", "🦖" to "avatar_trex",
    "🚗" to "avatar_car", "🚀" to "avatar_rocket", "🚂" to "avatar_train",
    "🚜" to "avatar_tractor", "🚁" to "avatar_helicopter", "⛵" to "avatar_sailboat",
    "🤖" to "avatar_robot", "👻" to "avatar_ghost", "🌟" to "avatar_star",
    "🌈" to "avatar_rainbow", "🍉" to "avatar_watermelon", "⚽" to "avatar_soccer",
    "🎸" to "avatar_guitar", "🧁" to "avatar_cupcake"
)

/**
 * Full-screen "Who's watching?" — one row of big tiles, D-pad and touch alike.
 * Picking a protected profile detours through the blind direction-PIN entry.
 * [remainingMinutes] (profile id → minutes left today) makes budget-stealing
 * visible on the tile itself; null entries show no number.
 */
@Composable
fun WhosWatchingScreen(
    profiles: List<Profile>,
    remainingMinutes: Map<String, Int?>,
    onOpenSettings: () -> Unit,
    onPicked: (Profile) -> Unit
) {
    var pinFor by remember { mutableStateOf<Profile?>(null) }

    pinFor?.let { profile ->
        DirectionPinScreen(
            title = "${profile.name}'s code",
            onCancel = { pinFor = null },
            onEntered = { entered ->
                if (entered == profile.pin) {
                    pinFor = null
                    onPicked(profile)
                    true
                } else false
            }
        )
        return
    }

    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    Surface(Modifier.fillMaxSize()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Text(
                "Who's watching?",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)
            )
            Spacer(Modifier.height(36.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
                contentPadding = PaddingValues(horizontal = 32.dp),
                modifier = Modifier.dpadHeldScrollThrottle(keys = DPAD_HORIZONTAL)
            ) {
                items(profiles.size, key = { profiles[it].id }) { i ->
                    val profile = profiles[i]
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = (if (i == 0) Modifier.focusRequester(firstFocus) else Modifier)
                            .tvFocusHighlight()
                            .clip(MaterialTheme.shapes.medium)
                            .clickable {
                                if (profile.pin != null) pinFor = profile else onPicked(profile)
                            }
                            .padding(12.dp)
                    ) {
                        ProfileAvatar(profile, size = 108)
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (profile.pin != null) {
                                Text("🔒 ", style = MaterialTheme.typography.bodySmall)
                            }
                            Text(
                                profile.name,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        remainingMinutes[profile.id]?.let { left ->
                            Text(
                                if (left <= 0) "no time left today" else "$left min left today",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(40.dp))
            // Still gated by the parent check inside — this is a doorway, not a hole.
            TextButton(
                onClick = onOpenSettings,
                modifier = Modifier.tvFocusHighlight()
            ) {
                Text(
                    "Parent settings",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/**
 * Blind PIN entry, Google-TV style: the code is four D-pad presses
 * (↑ ↓ ◀ ▶) and the screen shows only dots filling up — a sibling on the
 * couch sees nothing worth memorizing. Touch devices get the same four
 * arrows as buttons, so one code works everywhere.
 *
 * [onEntered] returns false on a wrong code; the dots reset with an error.
 */
@Composable
fun DirectionPinScreen(
    title: String,
    onCancel: () -> Unit,
    onEntered: (String) -> Boolean
) {
    var entered by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun press(dir: Char) {
        if (entered.length >= 4) return
        entered += dir
        if (entered.length == 4) {
            val attempt = entered
            entered = ""
            wrong = !onEntered(attempt)
        } else wrong = false
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionUp -> { press('U'); true }
                        Key.DirectionDown -> { press('D'); true }
                        Key.DirectionLeft -> { press('L'); true }
                        Key.DirectionRight -> { press('R'); true }
                        Key.Back, Key.Escape -> { onCancel(); true }
                        else -> false
                    }
                }
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "Press the secret arrows on the remote",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (wrong) {
                Spacer(Modifier.height(8.dp))
                Text("That wasn't it — try again", color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                repeat(4) { i ->
                    Box(
                        Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(
                                if (i < entered.length) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
            // Touch path: same four arrows as buttons. On TV these are never
            // reached — the key handler above consumes D-pad presses first.
            DirectionArrowPad(onPress = ::press)
            Spacer(Modifier.height(20.dp))
            TextButton(onClick = onCancel) { Text("Go back") }
        }
    }
}

/** The ↑/←→/↓ diamond used for entering and setting direction codes. */
@Composable
fun DirectionArrowPad(onPress: (Char) -> Unit) {
    @Composable
    fun arrow(label: String, dir: Char) {
        IconButton(
            onClick = { onPress(dir) },
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Text(label, style = MaterialTheme.typography.titleLarge)
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        arrow("↑", 'U')
        Row(verticalAlignment = Alignment.CenterVertically) {
            arrow("←", 'L')
            Spacer(Modifier.width(56.dp))
            arrow("→", 'R')
        }
        arrow("↓", 'D')
    }
}

/** Human-readable form of a stored code, for the parent editor only. */
fun directionPinArrows(pin: String): String =
    if (!isValidDirectionPin(pin)) pin
    else pin.map {
        when (it) {
            'U' -> '↑'; 'D' -> '↓'; 'L' -> '←'; else -> '→'
        }
    }.joinToString(" ")
