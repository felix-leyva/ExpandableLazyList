package de.ams.expandablelazylist

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastForEach
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

/**
 * PdfViewerComposable
 * A very simple composable that displays a PDF file and allows the user to zoom and pan the page.
 */
@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
internal fun PdfViewerComposable() {
    val context = LocalContext.current

    var pdfContent: Sequence<Bitmap>? by remember { mutableStateOf(null) }
    var boxConstraint: Constraints? by remember { mutableStateOf(null) }

    LaunchedEffect(Unit, boxConstraint) {
        val pageWidth =  boxConstraint?.maxWidth ?: return@LaunchedEffect
        val fileName = "sample2.pdf"
        val file = File(context.cacheDir, fileName)
        if (!file.exists()) {
            val pdfFile = context.assets.open(fileName)
            file.outputStream().use { output ->
                pdfFile.copyTo(output)
            }
        }
        val pdfRenderer =
            PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
        pdfContent = sequence {
            val pageCount = 20 //pdfRenderer.pageCount
            for (i in 0 until pageCount) {
                val page = pdfRenderer.openPage(i)
                val scale = pageWidth.toFloat() / page.width
                val height = page.height * scale
                val width = page.width * scale
                val bitmap = createBitmap(width = width.toInt(), height = height.toInt())
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                yield(bitmap)
            }
        }
    }

    AnimatedContent(pdfContent) {
        if (it != null) {
            boxConstraint?.let { constraints -> ExpandableLazyList(it.toList(), constraints) }
        } else {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                LaunchedEffect(Unit) {
                    boxConstraint = constraints
                }
                
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ExpandableLazyList(bitmaps: List<Bitmap>, constraints: Constraints) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    val listState = rememberLazyListState()
    val flingBehavior = ScrollableDefaults.flingBehavior()
    val scope = rememberCoroutineScope()
    var lastTapTime by remember { mutableLongStateOf(0L) }
    val unscaledWidth: Int by remember { derivedStateOf { constraints.minWidth } }
    val bitmapWidth by remember { derivedStateOf { unscaledWidth * scale } }
    val unscaledHeight by remember { derivedStateOf { constraints.minHeight }}
    val height by remember { derivedStateOf { unscaledHeight * scale } }
    
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .scrollbar(state = listState, horizontal = false)
    ) {
        LazyColumn(
            verticalArrangement = Arrangement.Top,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    var zoom = 1f
                    var pan = Offset.Zero
                    var pastTouchSlop = false
                    val touchSlop = viewConfiguration.touchSlop

                    awaitEachGesture {
                        val velocityTracker = VelocityTracker()
                        // Wait for a touch down event and get the pointer
                        val pointer = awaitFirstDown(requireUnconsumed = true)
                        // velocityTracker.addPosition(pointer.uptimeMillis, pointer.position)

                        val currentTapTime = pointer.uptimeMillis
                        if (currentTapTime - lastTapTime < 300) {
                            // Double tap detected
                            scale = when {
                                scale < 2f -> 2f
                                scale < 3f -> 3f
                                else -> {
                                    // Warning: Side effect to return the offset to 0
                                    offsetX = 0f
                                    offsetY = 0f
                                    1f
                                }
                            }
                            lastTapTime = 0L
                            pointer.consume()
                        } else {
                            lastTapTime = currentTapTime
                        }

                        do {
                            // Prepare for drag events and record velocity of a fling.
                            val event = awaitPointerEvent()
                            if (event.changes.fastAny { it.isConsumed }) break

                            // Add only drag events to the velocity tracker
                            if (event.changes.size == 1) {
                                event.changes.fastForEach(velocityTracker::addPointerInputChange)
                            }

                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()

                            // If very small changes, comulate the change until we pass the touch slop
                            if (!pastTouchSlop) {
                                zoom *= zoomChange
                                pan += panChange

                                val centroidSize = event.calculateCentroidSize(useCurrent = false)
                                val zoomMotion = abs(1 - zoom) * centroidSize

                                val panMotion = pan.getDistance()

                                if (zoomMotion > touchSlop || panMotion > touchSlop) {
                                    pastTouchSlop = true
                                }

                            } else if (zoomChange != 1f || panChange != Offset.Zero) {
                                // Change the scale between 1 and 3
                                scale = (scale * zoomChange).coerceIn(1.0f, 3f)

                                // Calculate the maximum offset we can move in x in each direction
                                val maxOffsetX = (bitmapWidth - unscaledWidth) / 2
                                offsetX = (offsetX + panChange.x).coerceIn(
                                    minimumValue = -maxOffsetX,
                                    maximumValue = maxOffsetX,
                                )

                                // Movement in y is only allowed if the image has an scale different from 1
                                //  and the image is not scrollable in the direction of the movement
                                //  We consume the offsetY events and scroll the list once the offsetY is again in 0
                                when {
                                    offsetY != 0f || (scale != 1f && (!listState.canScrollBackward && panChange.y > 0) || (!listState.canScrollForward && panChange.y < 0)) -> {
                                        val maxOffsetY = (height - unscaledHeight) / 2
                                        offsetY = (offsetY + panChange.y).coerceIn(
                                            minimumValue = -maxOffsetY,
                                            maximumValue = maxOffsetY,
                                        )
                                    }

                                    offsetY == 0f -> scope.launch { listState.scrollBy(-panChange.y / scale) }
                                }

                                // We consume the event to avoid interference with the list scrolling
                                // twice, as we already are scrolling the list manually
                                event.changes.fastForEach { it.consume() }
                            }
                        } while (event.changes.fastAny { it.pressed })

                        // We need to perform with the velocity we had at the end of the gesture a scroll
                        //  using the fling behavior of the lazy list
                        scope.launch {
                            listState.scroll {
                                with(flingBehavior) {
                                    performFling(-(velocityTracker.calculateVelocity()).y)
                                }
                            }
                        }
                    }
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                    translationY = offsetY
                },
            state = listState,
            userScrollEnabled = false,
        ) {
            bitmaps.forEach { page ->
                item {
                    Image(
                        modifier = Modifier
                            .padding(vertical = 16.dp)
                            .background(Color.White),
                        bitmap = page.asImageBitmap(),
                        contentDescription = "PDF Page",
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        }
    }
}


/**
 * Renders a scrollbar.
 *
 * <ul> <li> A scrollbar is composed of two components: a track and a knob. The knob moves across
 * the track <li> The scrollbar appears automatically when the user starts scrolling and disappears
 * after the scrolling is finished </ul>
 *
 * @param state The [LazyListState] that has been passed into the lazy list or lazy row
 * @param horiontal If `true`, this will be a horizontally-scrolling (left and right) scroll bar,
 * if `false`, it will be vertically-scrolling (up and down)
 * @param alignEnd If `true`, the scrollbar will appear at the "end" of the scrollable composable it
 * is decorating (at the right-hand side in left-to-right locales or left-hand side in right-to-left
 * locales, for the vertical scrollbars -or- the bottom for horizontal scrollbars). If `false`, the
 * scrollbar will appear at the "start" of the scrollable composable it is decorating (at the
 * left-hand side in left-to-right locales or right-hand side in right-to-left locales, for the
 * vertical scrollbars -or- the top for horizontal scrollbars)
 * @param thickness How thick/wide the track and knob should be
 * @param fixedKnobRatio If not `null`, the knob will always have this size, proportional to the
 * size of the track. You should consider doing this if the size of the items in the scrollable
 * composable is not uniform, to avoid the knob from oscillating in size as you scroll through the
 * list
 * @param knobCornerRadius The corner radius for the knob
 * @param trackCornerRadius The corner radius for the track
 * @param knobColor The color of the knob
 * @param trackColor The color of the track. Make it [Color.Transparent] to hide it
 * @param padding Edge padding to "squeeze" the scrollbar start/end in so it's not flush with the
 * contents of the scrollable composable it is decorating
 * @param visibleAlpha The alpha when the scrollbar is fully faded-in
 * @param hiddenAlpha The alpha when the scrollbar is fully faded-out. Use a non-`0` number to keep
 * the scrollbar from ever fading out completely
 * @param fadeInAnimationDurationMs The duration of the fade-in animation when the scrollbar appears
 * once the user starts scrolling
 * @param fadeOutAnimationDurationMs The duration of the fade-out animation when the scrollbar
 * disappears after the user is finished scrolling
 * @param fadeOutAnimationDelayMs Amount of time to wait after the user is finished scrolling before
 * the scrollbar begins its fade-out animation
 */
@Composable
fun Modifier.scrollbar(
    state: LazyListState,
    horizontal: Boolean,
    alignEnd: Boolean = true,
    thickness: Dp = 8.dp,
    fixedKnobRatio: Float? = null,
    knobCornerRadius: Dp = 4.dp,
    trackCornerRadius: Dp = 2.dp,
    knobColor: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
    padding: Dp = 0.dp,
    visibleAlpha: Float = 1f,
    hiddenAlpha: Float = 0f,
    fadeInAnimationDurationMs: Int = 150,
    fadeOutAnimationDurationMs: Int = 1500,
    fadeOutAnimationDelayMs: Int = 500,
): Modifier {
    check(thickness > 0.dp) { "Thickness must be a positive integer." }
    check(fixedKnobRatio == null || fixedKnobRatio < 1f) {
        "A fixed knob ratio must be smaller than 1."
    }
    check(knobCornerRadius >= 0.dp) { "Knob corner radius must be greater than or equal to 0." }
    check(trackCornerRadius >= 0.dp) { "Track corner radius must be greater than or equal to 0." }
    check(hiddenAlpha <= visibleAlpha) { "Hidden alpha cannot be greater than visible alpha." }
    check(fadeInAnimationDurationMs >= 0) {
        "Fade in animation duration must be greater than or equal to 0."
    }
    check(fadeOutAnimationDurationMs >= 0) {
        "Fade out animation duration must be greater than or equal to 0."
    }
    check(fadeOutAnimationDelayMs >= 0) {
        "Fade out animation delay must be greater than or equal to 0."
    }

    val targetAlpha = if (state.isScrollInProgress) {
        visibleAlpha
    } else {
        hiddenAlpha
    }
    val animationDurationMs = if (state.isScrollInProgress) {
        fadeInAnimationDurationMs
    } else {
        fadeOutAnimationDurationMs
    }
    val animationDelayMs = if (state.isScrollInProgress) {
        0
    } else {
        fadeOutAnimationDelayMs
    }

    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(delayMillis = animationDelayMs, durationMillis = animationDurationMs)
    )

    return drawWithContent {
        drawContent()

        state.layoutInfo.visibleItemsInfo.firstOrNull()?.let { firstVisibleItem ->
            if (state.isScrollInProgress || alpha > 0f) {
                // Size of the viewport, the entire size of the scrollable composable we are decorating with
                // this scrollbar.
                val viewportSize = if (horizontal) {
                    size.width
                } else {
                    size.height
                } - padding.toPx() * 2

                // The size of the first visible item. We use this to estimate how many items can fit in the
                // viewport. Of course, this works perfectly when all items have the same size. When they
                // don't, the scrollbar knob size will grow and shrink as we scroll.
                val firstItemSize = firstVisibleItem.size

                // The *estimated* size of the entire scrollable composable, as if it's all on screen at
                // once. It is estimated because it's possible that the size of the first visible item does
                // not represent the size of other items. This will cause the scrollbar knob size to grow
                // and shrink as we scroll, if the item sizes are not uniform.
                val estimatedFullListSize = firstItemSize * state.layoutInfo.totalItemsCount

                // The difference in position between the first pixels visible in our viewport as we scroll
                // and the top of the fully-populated scrollable composable, if it were to show all the
                // items at once. At first, the value is 0 since we start all the way to the top (or start
                // edge). As we scroll down (or towards the end), this number will grow.
                val viewportOffsetInFullListSpace =
                    state.firstVisibleItemIndex * firstItemSize + state.firstVisibleItemScrollOffset

                // Where we should render the knob in our composable.
                val knobPosition =
                    (viewportSize / estimatedFullListSize) * viewportOffsetInFullListSpace + padding.toPx()
                // How large should the knob be.
                val knobSize = fixedKnobRatio?.let { it * viewportSize }
                    ?: ((viewportSize * viewportSize) / estimatedFullListSize)

                // Draw the track
                drawRoundRect(
                    color = trackColor,
                    topLeft = when {
                        // When the scrollbar is horizontal and aligned to the bottom:
                        horizontal && alignEnd -> Offset(
                            padding.toPx(), size.height - thickness.toPx()
                        )
                        // When the scrollbar is horizontal and aligned to the top:
                        horizontal && !alignEnd -> Offset(padding.toPx(), 0f)
                        // When the scrollbar is vertical and aligned to the end:
                        alignEnd -> Offset(size.width - thickness.toPx(), padding.toPx())
                        // When the scrollbar is vertical and aligned to the start:
                        else -> Offset(0f, padding.toPx())
                    },
                    size = if (horizontal) {
                        Size(size.width - padding.toPx() * 2, thickness.toPx())
                    } else {
                        Size(thickness.toPx(), size.height - padding.toPx() * 2)
                    },
                    alpha = alpha,
                    cornerRadius = CornerRadius(
                        x = trackCornerRadius.toPx(), y = trackCornerRadius.toPx()
                    ),
                )

                // Draw the knob
                drawRoundRect(
                    color = knobColor,
                    topLeft = when {
                        // When the scrollbar is horizontal and aligned to the bottom:
                        horizontal && alignEnd -> Offset(
                            knobPosition, size.height - thickness.toPx()
                        )
                        // When the scrollbar is horizontal and aligned to the top:
                        horizontal && !alignEnd -> Offset(knobPosition, 0f)
                        // When the scrollbar is vertical and aligned to the end:
                        alignEnd -> Offset(size.width - thickness.toPx(), knobPosition)
                        // When the scrollbar is vertical and aligned to the start:
                        else -> Offset(0f, knobPosition)
                    },
                    size = if (horizontal) {
                        Size(knobSize, thickness.toPx())
                    } else {
                        Size(thickness.toPx(), knobSize)
                    },
                    alpha = alpha,
                    cornerRadius = CornerRadius(
                        x = knobCornerRadius.toPx(), y = knobCornerRadius.toPx()
                    ),
                )
            }
        }
    }
}
