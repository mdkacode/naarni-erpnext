package com.naarni.service.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
import androidx.compose.ui.input.pointer.positionChanged
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
                        Icons.AutoMirrored.Rounded.ArrowBack,
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

/** One photo — or one video — in a swipeable run. */
data class MediaPage(
    val key: String,
    val model: Any?,
    val title: String,
    val subtitle: String,
    val caption: String,
    val isVideo: Boolean = false,
)

/**
 * A swipeable run of photos, opened from the gallery at the one that was tapped.
 *
 * Browsing a vehicle's photos one-at-a-time — back out, tap the next, back out
 * again — is the single most tedious thing you can ask of someone standing at a
 * bus, so the gallery hands the whole set over and lets them flick through it.
 *
 * Flick-to-dismiss is deliberately *not* carried over from the single-photo
 * viewer: it fights a horizontal pager for the same drag, and losing a
 * comparison because a slightly diagonal swipe closed the screen is worse than
 * having to reach for Back. Back and the close button both still work.
 */
@Composable
fun MediaPagerViewer(
    pages: List<MediaPage>,
    startIndex: Int,
    onClose: () -> Unit,
) {
    if (pages.isEmpty()) {
        onClose()
        return
    }

    val pagerState = rememberPagerState(
        initialPage = startIndex.coerceIn(0, pages.lastIndex),
        pageCount = { pages.size },
    )
    var chrome by remember { mutableStateOf(true) }
    // Paging is locked while a photo is magnified, so a pan across a zoomed
    // image cannot slide the next one in from the side mid-inspection.
    var zoomedPage by remember { mutableStateOf(false) }

    BackHandler { onClose() }

    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !zoomedPage,
            modifier = Modifier.fillMaxSize(),
        ) { index ->
            val page = pages[index]
            if (page.isVideo) {
                VideoPage(
                    model = page.model,
                    active = index == pagerState.currentPage,
                    onChromeChange = { visible -> if (index == pagerState.currentPage) chrome = visible },
                )
            } else {
                ZoomableImage(
                    page = page,
                    active = index == pagerState.currentPage,
                    onZoomedChange = { if (index == pagerState.currentPage) zoomedPage = it },
                    onToggleChrome = { chrome = !chrome },
                )
            }
        }

        val current = pages[pagerState.currentPage.coerceIn(0, pages.lastIndex)]

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
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Close",
                        tint = Color.White,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        current.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        current.subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // Position in the run. Without it there is no way to tell a
                // one-photo thread from the last photo of forty.
                if (pages.size > 1) {
                    Text(
                        "${pagerState.currentPage + 1} / ${pages.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
        }

        if (current.caption.isNotBlank()) {
            AnimatedVisibility(
                visible = chrome,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Text(
                    current.caption,
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

/**
 * One page of the run: pinch and double-tap to zoom, drag to pan while zoomed.
 *
 * The gesture handling is written out by hand rather than using
 * `Modifier.transformable` because that detector consumes *every* drag past
 * touch slop, including a flat horizontal one — which means the pager
 * underneath never sees a swipe and the photos cannot be changed at all. This
 * consumes only what it actually needs: a genuine pinch (two fingers down), or
 * a drag while already magnified. At rest a one-finger swipe is left
 * untouched and falls through to the pager.
 */
@Composable
private fun ZoomableImage(
    page: MediaPage,
    active: Boolean,
    onZoomedChange: (Boolean) -> Unit,
    onToggleChrome: () -> Unit,
) {
    var scale by remember(page.key) { mutableFloatStateOf(1f) }
    var panX by remember(page.key) { mutableFloatStateOf(0f) }
    var panY by remember(page.key) { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()

    // Swiping away from a magnified photo resets it, so returning to it later
    // does not land you deep inside a corner you no longer remember choosing.
    LaunchedEffect(active) {
        if (!active) {
            scale = 1f
            panX = 0f
            panY = 0f
        }
    }

    LaunchedEffect(scale) { onZoomedChange(scale > ZOOMED) }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(page.key) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pinching = event.changes.count { it.pressed } >= 2
                        if (pinching || scale > ZOOMED) {
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            scale = (scale * zoomChange).coerceIn(1f, 5f)
                            if (scale > ZOOMED) {
                                panX += panChange.x
                                panY += panChange.y
                            } else {
                                panX = 0f
                                panY = 0f
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(page.key) {
                detectTapGestures(
                    onTap = { onToggleChrome() },
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
            model = page.model,
            contentDescription = page.caption.ifBlank { "Photo" },
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = panX
                    translationY = panY
                },
        ) {
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
    }
}
