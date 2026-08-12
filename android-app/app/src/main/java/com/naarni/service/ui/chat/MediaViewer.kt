package com.naarni.service.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Full-screen photo viewer.
 *
 * Opened by tapping a photo in a thread — until now tapping one did nothing at
 * all, which made every attachment look broken. Pinch and double-tap zoom, drag
 * to pan while zoomed, flick down to dismiss, and tap once to hide the chrome so
 * the photo of the damaged part is the only thing on screen.
 *
 * The canvas fades out as the photo is dragged away, so the gesture tells you it
 * is a dismissal before you commit to it.
 */
@Composable
fun MediaViewer(
    model: Any?,
    title: String,
    subtitle: String,
    caption: String,
    onClose: () -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    var dismissY by remember { mutableFloatStateOf(0f) }
    var chrome by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    val transform = rememberTransformableState { zoomChange, panChange, _ ->
        val next = (scale * zoomChange).coerceIn(1f, 5f)
        scale = next
        if (next > ZOOMED) {
            panX += panChange.x
            panY += panChange.y
            dismissY = 0f
        } else {
            // Not zoomed in: a vertical drag is a dismissal, not a pan.
            panX = 0f
            panY = 0f
            dismissY += panChange.y
        }
    }

    // A gesture only counts as a dismissal once the finger leaves the glass —
    // otherwise a fast pinch that momentarily drifts downward closes the viewer.
    LaunchedEffect(transform.isTransformInProgress) {
        if (transform.isTransformInProgress) return@LaunchedEffect
        when {
            scale <= ZOOMED && abs(dismissY) > DISMISS_PX -> onClose()
            dismissY != 0f -> animate(dismissY, 0f) { value, _ -> dismissY = value }
        }
    }

    BackHandler { onClose() }

    // Fully opaque until the drag starts, then thinning towards transparent.
    val scrim = (1f - abs(dismissY) / (DISMISS_PX * 3f)).coerceIn(0.35f, 1f)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = scrim))
            .transformable(transform)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { chrome = !chrome },
                    onDoubleTap = {
                        scope.launch {
                            val target = if (scale > ZOOMED) 1f else 2.5f
                            if (target == 1f) {
                                panX = 0f
                                panY = 0f
                            }
                            animate(scale, target) { value, _ -> scale = value }
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        SubcomposeAsyncImage(
            model = model,
            contentDescription = caption.ifBlank { "Photo" },
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = panX
                    translationY = panY + dismissY
                },
        ) {
            // A private Frappe file on a depot's link can take a while; a blank
            // black screen with no spinner reads as a crash.
            when (painter.state) {
                is AsyncImagePainter.State.Loading ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White)
                    }

                is AsyncImagePainter.State.Error ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Couldn't load this photo", color = Color.White)
                    }

                else -> SubcomposeAsyncImageContent()
            }
        }

        AnimatedVisibility(
            visible = chrome,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Close",
                        tint = Color.White,
                    )
                }
                Column {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                }
            }
        }

        if (caption.isNotBlank()) {
            AnimatedVisibility(
                visible = chrome,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Text(
                    caption,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.45f))
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}

/** Anything at or below this is "not zoomed"; float compare needs the slack. */
private const val ZOOMED = 1.01f
private const val DISMISS_PX = 170f
