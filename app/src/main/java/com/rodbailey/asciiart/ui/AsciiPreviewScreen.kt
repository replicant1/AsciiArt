package com.rodbailey.asciiart.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Paint as AndroidPaint
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rodbailey.asciiart.R
import androidx.compose.foundation.BorderStroke
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rodbailey.asciiart.camera.CameraFrameAnalyzer
import com.rodbailey.asciiart.processing.AsciiDisplayMode
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import kotlin.math.roundToInt

private const val TAG = "AsciiPreviewScreen"

// Slots in AsciiGridPreview's textMetricsCache, which holds the last computed cell size and
// the text metrics derived from it, so measureText() and textSize mutations are skipped on
// frames where the cell size has not changed (i.e. every frame in steady state).
private const val CACHE_CELL_WIDTH = 0
private const val CACHE_CELL_HEIGHT = 1
private const val CACHE_CHAR_WIDTH = 2
private const val CACHE_BASELINE_OFFSET = 3
private const val TEXT_METRICS_CACHE_SLOTS = 4

// Text height as a fraction of cell height. Set to slightly less than 1.0 so that characters
// with tall ascenders or deep descenders (e.g. '|', 'g', 'y') do not overflow into adjacent
// cells. The value was determined empirically: at 1.0 some glyphs clip; at 0.90 the gap is
// visually noticeable; 0.92 is the largest value that keeps all printable ASCII glyphs within
// their cell bounds.
private const val TEXT_SIZE_CELL_FRACTION = 0.92f

@Composable
fun AsciiPreviewScreen() {
    // Shared slider controls for both camera and video pipelines
    var scaleFactor by remember { mutableIntStateOf(8) }
    var contrastFactor by remember { mutableFloatStateOf(1.0f) }
    var colorEnabled by remember { mutableStateOf(false) }
    var displayMode by remember { mutableStateOf(AsciiDisplayMode.IMAGE) }
    
    // Tab selection state
    var selectedTab by remember { mutableIntStateOf(0) }
    
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Header
        Text(stringResource(R.string.app_title), style = MaterialTheme.typography.titleLarge)
        
        // Shared Controls Section (sliders, display mode, color toggle)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                stringResource(R.string.ascii_preview_scale_factor_label, scaleFactor),
                style = MaterialTheme.typography.labelLarge
            )
            Slider(
                value = scaleFactor.toFloat(),
                onValueChange = { scaleFactor = it.roundToInt() },
                valueRange = 2f..48f
            )
            Text(
                stringResource(R.string.ascii_preview_contrast_label, (contrastFactor * 100f).roundToInt()),
                style = MaterialTheme.typography.labelLarge
            )
            Slider(
                value = contrastFactor,
                onValueChange = { contrastFactor = it },
                valueRange = 0.2f..2.0f
            )
            DisplayModeChipBar(
                displayMode = displayMode,
                onDisplayModeChange = { displayMode = it },
                colorEnabled = colorEnabled,
                onColorEnabledChange = { colorEnabled = it },
                modifier = Modifier.fillMaxWidth()
            )
        }
        
        // Tab Selection
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text(stringResource(R.string.ascii_preview_tab_live_camera)) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text(stringResource(R.string.ascii_preview_tab_video_file)) }
            )
        }
        
        // Content Area (switches based on selected tab)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            when (selectedTab) {
                0 -> {
                    // Camera tab
                    if (!hasCameraPermission) {
                        Button(onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) }) {
                            Text(stringResource(R.string.camera_permission_request_button))
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                stringResource(R.string.camera_permission_required),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    } else {
                        CameraTabContent(
                            scaleFactor = scaleFactor,
                            contrastFactor = contrastFactor,
                            colorEnabled = colorEnabled,
                            displayMode = displayMode,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                
                1 -> {
                    // Video file tab
                    ExoPlayerVideoFileTab(
                        scaleFactor = scaleFactor,
                        contrastFactor = contrastFactor,
                        colorEnabled = colorEnabled,
                        displayMode = displayMode,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun CameraTabContent(
    scaleFactor: Int,
    contrastFactor: Float,
    colorEnabled: Boolean,
    displayMode: AsciiDisplayMode,
    modifier: Modifier = Modifier
) {
    var liveBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var liveAsciiText by remember { mutableStateOf("") }
    var liveAsciiColors by remember { mutableStateOf<IntArray?>(null) }
    
    Column(modifier = modifier.fillMaxSize()) {
        CameraAnalysisPipeline(
            scaleFactor = scaleFactor,
            contrastFactor = contrastFactor,
            colorEnabled = colorEnabled,
            displayMode = displayMode,
            onFrameProcessed = { bitmap, asciiText, asciiColors ->
                liveBitmap = bitmap
                liveAsciiText = asciiText
                liveAsciiColors = asciiColors
            }
        )

        val liveBitmapValue = liveBitmap
        if (liveBitmapValue != null) {
            when (displayMode) {
                AsciiDisplayMode.IMAGE -> {
                    ImagePreview(
                        bitmap = liveBitmapValue,
                        colorEnabled = colorEnabled,
                        asciiColors = liveAsciiColors,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                }

                AsciiDisplayMode.ASCII -> {
                    AsciiGridPreview(
                        bitmap = liveBitmapValue,
                        asciiText = liveAsciiText,
                        asciiColors = liveAsciiColors,
                        colorEnabled = colorEnabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.ascii_preview_waiting_for_frames), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun DisplayModeChipBar(
    displayMode: AsciiDisplayMode,
    onDisplayModeChange: (AsciiDisplayMode) -> Unit,
    colorEnabled: Boolean,
    onColorEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val chipColors = FilterChipDefaults.filterChipColors(
                containerColor = MaterialTheme.colorScheme.surface,
                selectedContainerColor = MaterialTheme.colorScheme.primary,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                labelColor = MaterialTheme.colorScheme.onSurface
            )
            val chipBorder = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            FilterChip(
                selected = displayMode == AsciiDisplayMode.IMAGE,
                onClick = { onDisplayModeChange(AsciiDisplayMode.IMAGE) },
                label = { Text(stringResource(R.string.ascii_preview_image_button)) },
                colors = chipColors,
                border = chipBorder
            )
            FilterChip(
                selected = displayMode == AsciiDisplayMode.ASCII,
                onClick = { onDisplayModeChange(AsciiDisplayMode.ASCII) },
                label = { Text(stringResource(R.string.ascii_preview_ascii_button)) },
                colors = chipColors,
                border = chipBorder
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.ascii_preview_colour_label))
            Switch(
                checked = colorEnabled,
                onCheckedChange = onColorEnabledChange
            )
        }
    }
}

@Composable
private fun CameraAnalysisPipeline(
    scaleFactor: Int,
    contrastFactor: Float,
    colorEnabled: Boolean,
    displayMode: AsciiDisplayMode,
    onFrameProcessed: (Bitmap, String, IntArray?) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentScaleFactor by rememberUpdatedState(scaleFactor)
    val currentContrastFactor by rememberUpdatedState(contrastFactor)
    val currentColorEnabled by rememberUpdatedState(colorEnabled)
    val currentDisplayMode by rememberUpdatedState(displayMode)
    val currentFrameCallback by rememberUpdatedState(onFrameProcessed)
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }

    DisposableEffect(lifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val analysisUseCase = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        val frameAnalyzer = CameraFrameAnalyzer(
            scaleFactorProvider = { currentScaleFactor },
            contrastFactorProvider = { currentContrastFactor },
            colorEnabledProvider = { currentColorEnabled },
            displayModeProvider = { currentDisplayMode },
            onFrameProcessed = currentFrameCallback
        )
        analysisUseCase.setAnalyzer(analysisExecutor, frameAnalyzer)

        cameraProviderFuture.addListener(
            {
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        analysisUseCase
                    )
                } catch (interruptedException: InterruptedException) {
                    Thread.currentThread().interrupt()
                    Log.e(TAG, "Camera provider setup interrupted", interruptedException)
                } catch (executionException: ExecutionException) {
                    Log.e(TAG, "Failed to obtain camera provider", executionException)
                } catch (securityException: SecurityException) {
                    Log.e(TAG, "Camera permission not granted", securityException)
                } catch (illegalStateException: IllegalStateException) {
                    Log.e(TAG, "Camera could not bind to lifecycle", illegalStateException)
                } catch (illegalArgumentException: IllegalArgumentException) {
                    Log.e(TAG, "Invalid camera binding arguments", illegalArgumentException)
                }
            },
            mainExecutor
        )

        onDispose {
            analysisUseCase.clearAnalyzer()
            if (cameraProviderFuture.isDone) {
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    cameraProvider.unbindAll()
                } catch (interruptedException: InterruptedException) {
                    Thread.currentThread().interrupt()
                    Log.e(TAG, "Camera provider teardown interrupted", interruptedException)
                } catch (executionException: ExecutionException) {
                    Log.e(TAG, "Failed to obtain camera provider on teardown", executionException)
                }
            }
            analysisExecutor.shutdown()
        }
    }
}

/**
 * Renders the de-res grid as coloured cells (Colour on) or as the grayscale bitmap
 * (Colour off).
 *
 * Both branches draw the scene as captured — no inversion. Only the letterbox
 * background is black, to match the surrounding UI. ASCII mode needs its glyph density
 * to track brightness because ink on a black background *is* the light, but Image mode
 * paints the luminance directly, so inverting it just yields a photographic negative.
 */
@Composable
fun ImagePreview(
    bitmap: Bitmap,
    colorEnabled: Boolean = false,
    asciiColors: IntArray? = null,
    modifier: Modifier = Modifier
) {
    // Colour mode paints the sampled cell colours, grayscale mode the luma bitmap. Either
    // way it is one small image — 135x240 at scaleFactor 8 on a Pixel 3 — scaled up with
    // no filtering, so cells stay hard-edged. Colour mode used to draw that by hand, one
    // drawRect per cell: 32,400 canvas ops per frame, ~972,000/sec at 30fps.
    //
    // asciiColors may be longer than width * height, because the camera path's shared
    // buffer is only ever grown. createBitmap needs the array to be *at least* that large
    // and ignores the tail, matching the old asciiColors[(y * width) + x] indexing.
    val source = if (colorEnabled && asciiColors != null) {
        Bitmap.createBitmap(asciiColors, bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
    } else {
        bitmap
    }

    // ContentScale.Fit performs the same aspect-fit-and-centre the manual loop did, and
    // FilterQuality.None is what keeps the cells blocky rather than interpolated.
    Image(
        bitmap = source.asImageBitmap(),
        contentDescription = stringResource(R.string.ascii_preview_image_content_description),
        modifier = modifier.background(Color.Black),
        contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.None
    )
}

@Composable
fun AsciiGridPreview(
    bitmap: Bitmap,
    asciiText: String,
    asciiColors: IntArray?,
    colorEnabled: Boolean,
    modifier: Modifier,
) {
    // AsciiArt.toAsciiText emits exactly bitmap.width characters per row, separated by
    // '\n', so row y starts at y * (bitmap.width + 1). Row bounds are pure arithmetic —
    // no newline scan and no offsets array, which previously cost an O(text) scan plus an
    // IntArray allocation on every frame.
    //
    // Both draw loops below then use drawText(asciiText, start, end, ...), which indexes
    // straight into the original string's char[]. The point of that is to avoid
    // asciiText.split('\n'), which allocated a List plus one String and one backing char[]
    // per row — on a Pixel 3 at scaleFactor 8 that is 240 rows of ~135 chars, roughly
    // 85 KB per frame, or ~2.5 MB/sec of GC pressure at 30fps.
    val rowStride = bitmap.width + 1
    val defaultAsciiColor = Color.White.toArgb()
    val gridWidthSampleChar = stringResource(R.string.grid_width_sample_char)
    val textPaint = remember {
        AndroidPaint().apply {
            isAntiAlias = true
            typeface = android.graphics.Typeface.MONOSPACE
        }
    }

    // Pre-allocated FontMetrics — avoids the new FontMetrics() allocation on every frame
    // that textPaint.fontMetrics produces. getFontMetrics(existing) fills it in-place.
    val fontMetricsCache = remember { AndroidPaint.FontMetrics() }

    // Slot layout and the -1f "not yet measured" sentinel are documented at the CACHE_*
    // constants above.
    val textMetricsCache = remember { FloatArray(TEXT_METRICS_CACHE_SLOTS) { -1f } }

    Canvas(
        modifier = modifier.background(Color.Black)
    ) {
        val sourceWidth = bitmap.width.toFloat()
        val sourceHeight = bitmap.height.toFloat()
        val sourceAspect = sourceWidth / sourceHeight
        val canvasAspect = size.width / size.height

        val drawWidth: Float
        val drawHeight: Float
        val drawOffsetX: Float
        val drawOffsetY: Float

        if (canvasAspect > sourceAspect) {
            drawHeight = size.height
            drawWidth = drawHeight * sourceAspect
            drawOffsetX = (size.width - drawWidth) / 2f
            drawOffsetY = 0f
        } else {
            drawWidth = size.width
            drawHeight = drawWidth / sourceAspect
            drawOffsetX = 0f
            drawOffsetY = (size.height - drawHeight) / 2f
        }

        if (asciiText.isNotEmpty()) {
            val cellWidth = drawWidth / bitmap.width
            val cellHeight = drawHeight / bitmap.height

            // Recompute text metrics only when cell dimensions change.
            // In steady state (no scale/canvas change) this block is skipped entirely,
            // saving 2x measureText() calls, 1x FontMetrics allocation, and 1-2x
            // textPaint.textSize mutations per frame.
            if (cellWidth != textMetricsCache[CACHE_CELL_WIDTH] || cellHeight != textMetricsCache[CACHE_CELL_HEIGHT]) {
                val baseTextSize = cellHeight * TEXT_SIZE_CELL_FRACTION
                textPaint.textSize = baseTextSize
                val sampleWidth = textPaint.measureText(gridWidthSampleChar).coerceAtLeast(1f)
                if (sampleWidth > cellWidth) {
                    textPaint.textSize = baseTextSize * (cellWidth / sampleWidth)
                }
                textPaint.getFontMetrics(fontMetricsCache)
                textMetricsCache[CACHE_BASELINE_OFFSET] = (cellHeight - (fontMetricsCache.bottom - fontMetricsCache.top)) / 2f - fontMetricsCache.top
                textMetricsCache[CACHE_CHAR_WIDTH] = textPaint.measureText(gridWidthSampleChar)
                textMetricsCache[CACHE_CELL_WIDTH] = cellWidth
                textMetricsCache[CACHE_CELL_HEIGHT] = cellHeight
            }
            val baselineOffset = textMetricsCache[CACHE_BASELINE_OFFSET]
            val charWidth = textMetricsCache[CACHE_CHAR_WIDTH]

            // Pre-compute the x origin so each character is centred in its cell.
            val rowStartX = drawOffsetX + (cellWidth - charWidth) / 2f

            val nativeCanvas = drawContext.canvas.nativeCanvas

            if (!colorEnabled) {
                // Non-colour: draw each row in a single drawText() call (~270 calls/frame
                // instead of ~36,450). Paint.letterSpacing pads the advance of each glyph
                // so characters stay centred in their cells.
                // drawText(String, start, end, ...) indexes directly into asciiText —
                // no row String objects needed.
                textPaint.color = defaultAsciiColor
                textPaint.letterSpacing = (cellWidth - charWidth) / textPaint.textSize
                for (y in 0 until bitmap.height) {
                    val rowStart = y * rowStride
                    if (rowStart >= asciiText.length) break
                    val rowEnd = minOf(rowStart + bitmap.width, asciiText.length)
                    val textY = drawOffsetY + (y * cellHeight) + baselineOffset
                    nativeCanvas.drawText(asciiText, rowStart, rowEnd, rowStartX, textY, textPaint)
                }
                textPaint.letterSpacing = 0f
            } else {
                // Colour mode: must draw per-character for individual colours.
                // Reuse a CharArray(1) to avoid 36K String allocations per frame.
                val singleChar = CharArray(1)
                for (y in 0 until bitmap.height) {
                    val rowStart = y * rowStride
                    if (rowStart >= asciiText.length) break
                    val rowEnd = minOf(rowStart + bitmap.width, asciiText.length)
                    val textY = drawOffsetY + (y * cellHeight) + baselineOffset
                    for (x in 0 until bitmap.width) {
                        val pixelIndex = (y * bitmap.width) + x
                        textPaint.color = asciiColors?.getOrNull(pixelIndex) ?: defaultAsciiColor
                        singleChar[0] = if (rowStart + x < rowEnd) asciiText[rowStart + x] else ' '
                        val textX = rowStartX + (x * cellWidth)
                        nativeCanvas.drawText(singleChar, 0, 1, textX, textY, textPaint)
                    }
                }
            }
        }
    }
}
