package de.ams.expandablelazylist

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastForEach
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs
import kotlin.math.absoluteValue

/**
 * PdfViewerComposable
 * A very simple composable that displays a PDF file and allows the user to zoom and pan the page.
 *
 */
@Composable
internal fun PdfViewerComposable() {
    val context = LocalContext.current

    var pdfContent: Sequence<Bitmap>? by remember { mutableStateOf(null) }

    val density = LocalDensity.current

    LaunchedEffect(Unit) {
        val file = File(context.cacheDir, "sample.pdf")
        if (!file.exists()) {
            val pdfFile = context.assets.open("sample.pdf")
            file.outputStream().use { output ->
                pdfFile.copyTo(output)
            }
        }
        val pdfRenderer =
            PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
        pdfContent = sequence {
            for (i in 0 until pdfRenderer.pageCount) {
                val page = pdfRenderer.openPage(i)
                val height = page.height * density.density.toInt()
                val width = page.width * density.density.toInt()
                val bitmap = createBitmap(width = width, height = height)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                page.close()
                yield(bitmap)
            }
        }
    }

    AnimatedContent(pdfContent) {
        if (it != null) {
            ExpandalbeLazyList(it)
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }
    }

}


@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ExpandalbeLazyList(bitmaps: Sequence<Bitmap>) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    val gridState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var lastTapTime by remember { mutableLongStateOf(0L) }
    val bitmapWidth by remember { derivedStateOf { bitmaps.first().width * scale } }

    LazyColumn(
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
                    velocityTracker.addPosition(pointer.uptimeMillis, pointer.position)

                    val currentTapTime = pointer.uptimeMillis
                    if (currentTapTime - lastTapTime < 300) {
                        // Double tap detected
                        scale = when {
                            scale < 2f -> 2f
                            scale < 3f -> 3f
                            else -> 1f
                        }
                        lastTapTime = 0L
                    } else {
                        lastTapTime = currentTapTime
                    }

                    do {
                        // Prepare for drag events and record velocity of a fling.
                        val event = awaitPointerEvent()
                        var canceled = event.changes.fastAny { it.isConsumed }

                        if (!canceled) {
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()

                            if (!pastTouchSlop) {
                                zoom *= zoomChange
                                pan += panChange

                                val centroidSize = event.calculateCentroidSize(useCurrent = false)
                                val zoomMotion = abs(1 - zoom) * centroidSize

                                val panMotion = pan.getDistance()

                                if (zoomMotion > touchSlop || panMotion > touchSlop) {
                                    pastTouchSlop = true
                                }
                            }

                            if (pastTouchSlop) {
                                event.changes.fastForEach { change ->
                                    velocityTracker.addPosition(
                                        timeMillis = change.uptimeMillis, position = change.position
                                    )
                                }

                                val velocity = (velocityTracker.calculateVelocity())

                                // TODO: this is based in manual test, we need to improve this or find a better way to handle this
                                val consumeHere =
                                    zoomChange != 1f || panChange.x.absoluteValue > 5f || velocity.x.absoluteValue > 400f

                                if (consumeHere) {
                                    scale = (scale * zoomChange).coerceIn(1.0f, 3f)
                                    val maxOffsetX = (bitmapWidth - size.width) / 2
                                    offsetX = (offsetX + panChange.x).coerceIn(
                                        -maxOffsetX, maxOffsetX
                                    )

                                    scope.launch {
                                        gridState.scrollBy(-panChange.y / scale)
                                    }
                                    event.changes.fastForEach {
                                        if (it.positionChanged()) {
                                            it.consume()
                                        }
                                    }
                                }
                            }
                        }
                    } while (!canceled && event.changes.fastAny { it.pressed })
                }
            }
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offsetX,
            ),
        state = gridState,
        verticalArrangement = Arrangement.Center,
        userScrollEnabled = true,
    ) {
        bitmaps.forEach { page ->
            item {
                Image(
                    modifier = Modifier
                        .padding(16.dp)
                        .background(Color.White),
                    bitmap = page.asImageBitmap(),
                    contentDescription = "PDF Page",
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }
}
