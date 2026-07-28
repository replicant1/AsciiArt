package com.rodbailey.asciiart.ui

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
    var videoBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var asciiText by remember { mutableStateOf("") }
    var asciiColors by remember { mutableStateOf<IntArray?>(null) }
    var frameCount by remember { mutableStateOf(0) }

    // Wrap state updates in rememberUpdatedState so callback always references current state
    val currentBitmapSetter = rememberUpdatedState { bitmap: Bitmap? ->
        videoBitmap = bitmap
    }
    val currentTextSetter = rememberUpdatedState { text: String ->
        asciiText = text
    }
    val currentColorsSetter = rememberUpdatedState { colors: IntArray? ->
        asciiColors = colors
    }
    val currentFrameCounter = rememberUpdatedState {
        frameCount++
    }

    DisposableEffect(Unit) {
        val player = ExoPlayer.Builder(context).build()
        exoPlayer = player

        // Load test video file
        val mediaItem = MediaItem.fromUri(Uri.parse(TEST_VIDEO_PATH))
        player.setMediaItem(mediaItem)
        player.prepare()

        // Create and start frame listener
        val listener = ExoPlayerFrameListener(
            exoPlayer = player,
            videoUri = TEST_VIDEO_PATH.removePrefix("file://"),
            scaleFactorProvider = { scaleFactor },
            contrastFactorProvider = { contrastFactor },
            colorEnabledProvider = { colorEnabled },
            displayModeProvider = { displayMode },
            frameSkipRate = 2,  // Process every 2nd rendered frame
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

    Column(modifier = modifier.fillMaxSize()) {
        // Display content - determine what to show based on displayMode
        val showAscii = displayMode != AsciiDisplayMode.IMAGE_ONLY
        
        if (!showAscii) {
            // Video display (ExoPlayer handles rendering)
            exoPlayer?.let {
                AndroidView(
                    factory = { context ->
                        StyledPlayerView(context).apply {
                            player = it
                            useController = true
                            controllerShowTimeoutMs = 5000
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            } ?: run {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Initializing player...")
                }
            }
        } else {
            // ASCII display
            if (videoBitmap != null) {
                AsciiGridPreview(
                    bitmap = videoBitmap!!,
                    asciiText = asciiText,
                    asciiColors = asciiColors,
                    colorEnabled = colorEnabled,
                    drawSourceImage = displayMode == AsciiDisplayMode.ASCII_OVERLAY,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Waiting for video frames...")
                }
            }
        }
    }
}
