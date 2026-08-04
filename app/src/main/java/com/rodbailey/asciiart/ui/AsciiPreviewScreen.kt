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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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
                onValueChange = { scaleFactor = it.roundToInt().coerceIn(2, 48) },
                valueRange = 2f..48f
            )
            Text(
                stringResource(R.string.ascii_preview_contrast_label, (contrastFactor * 100f).roundToInt()),
                style = MaterialTheme.typography.labelLarge
            )
            Slider(
                value = contrastFactor,
                onValueChange = { contrastFactor = it.coerceIn(0.2f, 2.0f) },
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
                text = { Text("Live Camera") }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Video File") }
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
                    VideoFileTabContent(
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
                        drawSourceImage = false,
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
private fun VideoFileTabContent(
    scaleFactor: Int,
    contrastFactor: Float,
    colorEnabled: Boolean,
    displayMode: AsciiDisplayMode,
    modifier: Modifier = Modifier
) {
    ExoPlayerVideoFileTab(
        scaleFactor = scaleFactor,
        contrastFactor = contrastFactor,
        colorEnabled = colorEnabled,
        displayMode = displayMode,
        modifier = modifier
    )
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
    if (colorEnabled && asciiColors != null) {
        val cellPaint = remember { AndroidPaint() }
        Canvas(modifier = modifier.background(Color.Black)) {
            val sourceAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
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
            val cellWidth = drawWidth / bitmap.width
            val cellHeight = drawHeight / bitmap.height
            val nativeCanvas = drawContext.canvas.nativeCanvas
            for (y in 0 until bitmap.height) {
                val top = drawOffsetY + y * cellHeight
                val bottom = top + cellHeight
                for (x in 0 until bitmap.width) {
                    cellPaint.color = asciiColors[(y * bitmap.width) + x]
                    val left = drawOffsetX + x * cellWidth
                    nativeCanvas.drawRect(left, top, left + cellWidth, bottom, cellPaint)
                }
            }
        }
    } else {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Processed image preview",
            modifier = modifier.background(Color.Black),
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.None
        )
    }
}

@Composable
fun AsciiGridPreview(
    bitmap: Bitmap,
    asciiText: String,
    asciiColors: IntArray?,
    colorEnabled: Boolean,
    drawSourceImage: Boolean,
    modifier: Modifier,
) {
    // Build an IntArray of row-start indices by scanning asciiText once for '\n'.
    // Replaces asciiText.split('\n') which allocated the following per frame:
    //   - 1 List<String> wrapper
    //   - 240 String objects (one per row; count = bitmap.height = 1920/scaleFactor on Pixel 3)
    //   - 240 backing char[] arrays (ART does not share buffers between split substrings)
    //   Each row is ~135 chars (bitmap.width = 1080/scaleFactor), so each char[] is
    //   ~286 bytes → total ~85 KB allocated per frame → ~2.5 MB/sec of GC pressure at 30fps.
    // Now: one IntArray(241) ≈ 964 bytes. drawText(asciiText, rowStart, rowEnd, ...) reads
    // directly from the original string's char[] with no copy and no new heap objects.
    // rowOffsets[y] is the index in asciiText where row y begins.
    val rowOffsets = remember(asciiText) {
        val newlineCount = asciiText.count { it == '\n' }
        val offsets = IntArray(newlineCount + 1)
        offsets[0] = 0
        var row = 1
        for (i in asciiText.indices) {
            if (asciiText[i] == '\n' && row <= newlineCount) offsets[row++] = i + 1
        }
        offsets
    }
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val defaultAsciiColor = Color.White.toArgb()
    val gridWidthSampleChar = stringResource(R.string.grid_width_sample_char)
    val textPaint = remember {
        AndroidPaint().apply {
            isAntiAlias = true
            typeface = android.graphics.Typeface.MONOSPACE
        }
    }
    textPaint.color = defaultAsciiColor

    // Pre-allocated FontMetrics — avoids the new FontMetrics() allocation on every frame
    // that textPaint.fontMetrics produces. getFontMetrics(existing) fills it in-place.
    val fontMetricsCache = remember { AndroidPaint.FontMetrics() }

    // Caches the last computed cell dimensions and their derived text metrics so that
    // measureText() and textSize mutations are skipped on frames where the cell size
    // is unchanged (i.e. every frame in steady state).
    val textMetricsCache = remember { FloatArray(4) { -1f } }
    val CACHE_CELL_WIDTH = 0
    val CACHE_CELL_HEIGHT = 1
    val CACHE_CHAR_WIDTH = 2
    val CACHE_BASELINE_OFFSET = 3

    // Text height as a fraction of cell height. Set to slightly less than 1.0 so that
    // characters with tall ascenders or deep descenders (e.g. '|', 'g', 'y') do not
    // overflow into adjacent cells. The value was determined empirically: at 1.0 some
    // glyphs clip; at 0.90 the gap is visually noticeable; 0.92 is the largest value
    // that keeps all printable ASCII glyphs within their cell bounds.
    val TEXT_SIZE_CELL_FRACTION = 0.92f

    Canvas(
        modifier = modifier.background(Color.Black)
    ) {
        val imageBitmap = bitmap.asImageBitmap()
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

        if (drawSourceImage) {
            drawImage(
                image = imageBitmap,
                dstOffset = IntOffset(drawOffsetX.toInt(), drawOffsetY.toInt()),
                dstSize = IntSize(drawWidth.toInt(), drawHeight.toInt()),
                filterQuality = FilterQuality.None
            )
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
                    val rowStart = rowOffsets.getOrNull(y) ?: continue
                    val rowEnd = if (y + 1 < rowOffsets.size) rowOffsets[y + 1] - 1 else asciiText.length
                    if (rowStart >= rowEnd) continue
                    val textY = drawOffsetY + (y * cellHeight) + baselineOffset
                    nativeCanvas.drawText(asciiText, rowStart, rowEnd, rowStartX, textY, textPaint)
                }
                textPaint.letterSpacing = 0f
            } else {
                // Colour mode: must draw per-character for individual colours.
                // Reuse a CharArray(1) to avoid 36K String allocations per frame.
                val singleChar = CharArray(1)
                for (y in 0 until bitmap.height) {
                    val rowStart = rowOffsets.getOrNull(y) ?: continue
                    val rowEnd = if (y + 1 < rowOffsets.size) rowOffsets[y + 1] - 1 else asciiText.length
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
