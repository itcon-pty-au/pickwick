package io.pickwick.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.pickwick.app.data.QuizQuestion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One quiz in front of a kid; [canEarn] decides whether time is mentioned at all. */
internal class QuizSession(val questions: List<QuizQuestion>, val canEarn: Boolean)

/** A question left alone this long means nobody is there: move on rather than hold the TV. */
private const val IDLE_TIMEOUT_MS = 60_000L
private const val RESULT_TIMEOUT_MS = 10_000L

/**
 * The quiz, drawn over the finished video. Skip and Back leave at any point, and
 * playback has ended, so nothing here draws on the kid's screen time. Takes real
 * Compose focus, unlike the TV player controls: [PlayerActivity.onKeyDown] stands
 * aside while this is up.
 */
@Composable
internal fun QuizOverlay(
    session: QuizSession,
    /** Records the answer and returns the seconds it earned. */
    onAnswered: suspend (correct: Boolean) -> Int,
    onDone: () -> Unit
) {
    var index by remember { mutableIntStateOf(0) }
    var picked by remember { mutableStateOf<Int?>(null) }
    var correctCount by remember { mutableIntStateOf(0) }
    var earnedSeconds by remember { mutableIntStateOf(0) }
    var finished by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val question = session.questions[index]

    BackHandler(onBack = onDone)
    LaunchedEffect(index, picked, finished) {
        runCatching { focus.requestFocus() }
        delay(if (finished) RESULT_TIMEOUT_MS else IDLE_TIMEOUT_MS)
        onDone()
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF7101214))
            // Touches stop here, not on the finished player's controls underneath.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                // Overscan-safe on a TV, and clear of the edges on a phone.
                .padding(horizontal = 32.dp, vertical = 24.dp)
                // The video-ending press may still be held as this appears.
                .ignoreSelectUntilRelease()
        ) {
            if (finished) {
                Text(
                    "You got $correctCount of ${session.questions.size}!",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center
                )
                if (earnedSeconds > 0) {
                    Text(
                        "+${formatBonusTime(earnedSeconds)} of bonus time today",
                        color = QuizGreen,
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = onDone, modifier = Modifier.focusRequester(focus)) { Text("Continue") }
                return@Column
            }

            Text(
                "Quick quiz  ·  ${index + 1} of ${session.questions.size}",
                color = Color.White.copy(alpha = 0.72f),
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                question.text,
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Medium),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            question.options.forEachIndexed { i, option ->
                val answered = picked != null
                val resultFill = when {
                    !answered -> MaterialTheme.colorScheme.secondaryContainer
                    i == question.answerIndex -> QuizGreen
                    i == picked -> QuizWrongAmber
                    else -> Color.White.copy(alpha = 0.08f)
                }
                Button(
                    enabled = !answered,
                    onClick = {
                        val correct = i == question.answerIndex
                        picked = i
                        if (correct) correctCount++
                        scope.launch { earnedSeconds += onAnswered(correct) }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = resultFill,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        disabledContainerColor = resultFill,
                        disabledContentColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                        .then(if (!answered && i == 0) Modifier.focusRequester(focus) else Modifier)
                ) { Text(option, textAlign = TextAlign.Center) }
            }
            picked?.let {
                if (question.why.isNotEmpty()) {
                    Text(
                        question.why,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                }
                Button(
                    onClick = {
                        if (index < session.questions.lastIndex) {
                            index++
                            picked = null
                        } else finished = true
                    },
                    modifier = Modifier.focusRequester(focus)
                ) { Text(if (index < session.questions.lastIndex) "Next" else "Finish") }
            }
            TextButton(onClick = onDone) { Text("Skip quiz", color = Color.White.copy(alpha = 0.8f)) }
        }
    }
}

private fun formatBonusTime(seconds: Int): String = when {
    seconds < 60 -> "$seconds sec"
    seconds % 60 == 0 -> "${seconds / 60} min"
    else -> "${seconds / 60} min ${seconds % 60} sec"
}

/** Green "?" badge and line telling a kid a quiz follows. A chip, so it never covers the picture. */
@Composable
internal fun QuizHintChip(text: String, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(18.dp).background(QuizGreen, CircleShape)
        ) {
            Text("?", color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.width(8.dp))
        Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 2)
    }
}
