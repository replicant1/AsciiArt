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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
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
    var scaleFactor by remember { mutableIntStateOf(8) }
    var contrastFactor by remember { mutableFloatStateOf(1.0f) }
    var invertEnabled by remember { mutableStateOf(false) }
    var displayMode by remember { mutableStateOf(AsciiDisplayMode.IMAGE_ONLY) }
    var liveBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var liveAsciiText by remember { mutableStateOf("") }

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
        Text("ASCII Art", style = MaterialTheme.typography.titleLarge)
        Text("Scale factor: $scaleFactor x", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = scaleFactor.toFloat(),
            onValueChange = { scaleFactor = it.roundToInt().coerceIn(2, 24) },
            valueRange = 2f..24f
        )
        Text("Contrast: ${(contrastFactor * 100f).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = contrastFactor,
            onValueChange = { contrastFactor = it.coerceIn(0.2f, 2.0f) },
            valueRange = 0.2f..2.0f
        )
        DisplayModeChipBar(
            displayMode = displayMode,
            onDisplayModeChange = { displayMode = it },
            invertEnabled = invertEnabled,
            onInvertEnabledChange = { invertEnabled = it },
            modifier = Modifier.fillMaxWidth()
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (!hasCameraPermission) {
                Button(onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("Grant permission")
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Camera access required", style = MaterialTheme.typography.labelMedium)
                }
            } else {
                CameraAnalysisPipeline(
                    scaleFactor = scaleFactor,
                    contrastFactor = contrastFactor,
                    invertEnabled = invertEnabled,
                    displayMode = displayMode,
                    onFrameProcessed = { bitmap, asciiText, _, _ ->
                        liveBitmap = bitmap
                        liveAsciiText = asciiText
                    }
                )

                val liveBitmapValue = liveBitmap
                if (liveBitmapValue != null) {
                    when (displayMode) {
                        AsciiDisplayMode.IMAGE_ONLY -> {
                            ImagePreview(
                                bitmap = liveBitmapValue,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                            )
                        }

                        AsciiDisplayMode.ASCII_OVERLAY -> {
                            AsciiGridPreview(
                                bitmap = liveBitmapValue,
                                asciiText = liveAsciiText,
                                drawSourceImage = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                            )
                        }

                        AsciiDisplayMode.ASCII_ONLY -> {
                            AsciiGridPreview(
                                bitmap = liveBitmapValue,
                                asciiText = liveAsciiText,
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
                        Text("Waiting for frames...", style = MaterialTheme.typography.labelMedium)
                    }
                }

            }
        }

    }
}

@Composable
private fun DisplayModeChipBar(
    displayMode: AsciiDisplayMode,
    onDisplayModeChange: (AsciiDisplayMode) -> Unit,
    invertEnabled: Boolean,
    onInvertEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val transparentChipColors = FilterChipDefaults.filterChipColors(
                containerColor = Color.Transparent,
                selectedContainerColor = Color.Transparent
            )
            val chipBorder = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            FilterChip(
                selected = displayMode == AsciiDisplayMode.IMAGE_ONLY,
                onClick = { onDisplayModeChange(AsciiDisplayMode.IMAGE_ONLY) },
                label = { Text("Image") },
                colors = transparentChipColors,
                border = chipBorder
            )
            FilterChip(
                selected = displayMode == AsciiDisplayMode.ASCII_OVERLAY,
                onClick = { onDisplayModeChange(AsciiDisplayMode.ASCII_OVERLAY) },
                label = { Text("Overlay") },
                colors = transparentChipColors,
                border = chipBorder
            )
            FilterChip(
                selected = displayMode == AsciiDisplayMode.ASCII_ONLY,
                onClick = { onDisplayModeChange(AsciiDisplayMode.ASCII_ONLY) },
                label = { Text("ASCII") },
                colors = transparentChipColors,
                border = chipBorder
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Invert")
            Switch(
                checked = invertEnabled,
                onCheckedChange = onInvertEnabledChange
            )
        }
    }
}

@Composable
private fun CameraAnalysisPipeline(
    scaleFactor: Int,
    contrastFactor: Float,
    invertEnabled: Boolean,
    displayMode: AsciiDisplayMode,
    onFrameProcessed: (Bitmap, String, Double, Double) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentScaleFactor by rememberUpdatedState(scaleFactor)
    val currentContrastFactor by rememberUpdatedState(contrastFactor)
    val currentInvertEnabled by rememberUpdatedState(invertEnabled)
    val currentDisplayMode by rememberUpdatedState(displayMode)
    val currentFrameCallback by rememberUpdatedState(onFrameProcessed)
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }

    DisposableEffect(lifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val analysisUseCase = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        analysisUseCase.setAnalyzer(
            analysisExecutor,
            CameraFrameAnalyzer(
                scaleFactorProvider = { currentScaleFactor },
                contrastFactorProvider = { currentContrastFactor },
                invertEnabledProvider = { currentInvertEnabled },
                displayModeProvider = { currentDisplayMode },
                onFrameProcessed = currentFrameCallback
            )
        )

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

@Composable
private fun ImagePreview(bitmap: Bitmap, modifier: Modifier = Modifier) {
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "Processed image preview",
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.None
    )
}

@Composable
private fun AsciiGridPreview(
    bitmap: Bitmap,
    asciiText: String,
    drawSourceImage: Boolean,
    modifier: Modifier,
) {
    val rows = remember(asciiText) { asciiText.split('\n') }
    val textPaint = remember {
        AndroidPaint().apply {
            isAntiAlias = true
            typeface = android.graphics.Typeface.MONOSPACE
        }
    }
    textPaint.color = MaterialTheme.colorScheme.onSurface.toArgb()

    Canvas(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant)
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

        if (rows.isNotEmpty()) {
            val cellWidth = drawWidth / bitmap.width
            val cellHeight = drawHeight / bitmap.height
            val baseTextSize = cellHeight * 0.92f
            textPaint.textSize = baseTextSize
            val sampleWidth = textPaint.measureText("W").coerceAtLeast(1f)
            if (sampleWidth > cellWidth) {
                textPaint.textSize = baseTextSize * (cellWidth / sampleWidth)
            }
            val fontMetrics = textPaint.fontMetrics
            val baselineOffset = (cellHeight - (fontMetrics.bottom - fontMetrics.top)) / 2f - fontMetrics.top

            val nativeCanvas = drawContext.canvas.nativeCanvas
            for (y in 0 until bitmap.height) {
                val row = rows.getOrNull(y).orEmpty()
                val top = drawOffsetY + (y * cellHeight)
                for (x in 0 until bitmap.width) {
                    val char = row.getOrElse(x) { ' ' }
                    val text = char.toString()
                    val charWidth = textPaint.measureText(text)
                    val left = drawOffsetX + (x * cellWidth)
                    val textX = left + ((cellWidth - charWidth) / 2f)
                    val textY = top + baselineOffset
                    nativeCanvas.drawText(text, textX, textY, textPaint)
                }
            }
        }
    }
}
