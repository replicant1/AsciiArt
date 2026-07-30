package com.rodbailey.asciiart.ui

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.ui.StyledPlayerView
import com.rodbailey.asciiart.processing.AsciiDisplayMode
import com.rodbailey.asciiart.processing.ExoPlayerFrameListener

private const val TAG = "VideoFilePlayer"

// Path to test video file on device
private const val TEST_VIDEO_PATH = "file:///sdcard/Download/blue-eyes.mp4"

@Composable
fun ExoPlayerVideoFileTab(
    scaleFactor: Int,
    contrastFactor: Float,
    colorEnabled: Boolean,
    displayMode: AsciiDisplayMode,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var exoPlayer by remember { mutableStateOf<ExoPlayer?>(null) }
    var frameListener by remember { mutableStateOf<ExoPlayerFrameListener?>(null) }
    var captureTextureView by remember { mutableStateOf<TextureView?>(null) }
    var videoBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var asciiText by remember { mutableStateOf("") }
    var asciiColors by remember { mutableStateOf<IntArray?>(null) }
    var frameCount by remember { mutableStateOf(0) }

    // Wrap parameters in rememberUpdatedState so callbacks/lambdas always reference current values
    val currentScaleFactor = rememberUpdatedState(scaleFactor)
    val currentContrastFactor = rememberUpdatedState(contrastFactor)
    val currentColorEnabled = rememberUpdatedState(colorEnabled)
    val currentDisplayMode = rememberUpdatedState(displayMode)
    val currentCaptureTextureView = rememberUpdatedState(captureTextureView)

    val currentBitmapSetter = rememberUpdatedState { bitmap: Bitmap? -> videoBitmap = bitmap }
    val currentTextSetter = rememberUpdatedState { text: String -> asciiText = text }
    val currentColorsSetter = rememberUpdatedState { colors: IntArray? -> asciiColors = colors }
    val currentFrameCounter = rememberUpdatedState { frameCount++ }

    DisposableEffect(Unit) {
        val player = ExoPlayer.Builder(context).build()
        exoPlayer = player

        val mediaItem = MediaItem.fromUri(Uri.parse(TEST_VIDEO_PATH))
        player.setMediaItem(mediaItem)
        player.prepare()

        val listener = ExoPlayerFrameListener(
            exoPlayer = player,
            textureViewProvider = { currentCaptureTextureView.value },
            scaleFactorProvider = { currentScaleFactor.value },
            contrastFactorProvider = { currentContrastFactor.value },
            colorEnabledProvider = { currentColorEnabled.value },
            displayModeProvider = { currentDisplayMode.value },
            frameSkipRate = 2,
            onFrameProcessed = { bitmap, ascii, colors ->
                currentBitmapSetter.value(bitmap)
                currentTextSetter.value(ascii)
                currentColorsSetter.value(colors)
                currentFrameCounter.value()
            }
        )
        frameListener = listener
        listener.startListening()
        player.play()

        onDispose {
            listener.release()
            player.release()
            exoPlayer = null
            frameListener = null
        }
    }

    // Switch ExoPlayer's render target when the display mode changes.
    // - ASCII mode: render to the hidden TextureView so getBitmap() can capture frames.
    // - IMAGE mode: clear the TextureView; the StyledPlayerView re-attaches ExoPlayer
    //   to its own surface when it enters the composition.
    LaunchedEffect(displayMode, exoPlayer) {
        val player = exoPlayer ?: return@LaunchedEffect
        when (displayMode) {
            AsciiDisplayMode.ASCII -> {
                captureTextureView?.let { player.setVideoTextureView(it) }
            }
            AsciiDisplayMode.IMAGE -> {
                captureTextureView?.let { player.clearVideoTextureView(it) }
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        val showAscii = displayMode != AsciiDisplayMode.IMAGE

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            // Hidden TextureView — always present so ExoPlayer can render decoded frames
            // to it in ASCII mode. getBitmap() reads from this surface.
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).also { captureTextureView = it }
                },
                modifier = Modifier.fillMaxSize()
            )

            if (!showAscii) {
                // IMAGE mode: StyledPlayerView covers the TextureView and takes over
                // as ExoPlayer's render target (set via its player property).
                exoPlayer?.let { player ->
                    AndroidView(
                        factory = { ctx ->
                            StyledPlayerView(ctx).apply {
                                this.player = player
                                useController = true
                                controllerShowTimeoutMs = 5000
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } ?: Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Initializing player...")
                }
            } else {
                // ASCII mode: render ASCII art over the hidden TextureView.
                val bitmap = videoBitmap
                if (bitmap != null) {
                    AsciiGridPreview(
                        bitmap = bitmap,
                        asciiText = asciiText,
                        asciiColors = asciiColors,
                        colorEnabled = colorEnabled,
                        drawSourceImage = false,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Waiting for video frames...")
                    }
                }
            }
        }
    }
}

