package com.rodbailey.asciiart.ui

import android.content.Context
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.ui.StyledPlayerView
import com.rodbailey.asciiart.processing.AsciiDisplayMode

private const val TAG = "VideoFilePlayer"

// Path to test video file in app assets
private const val TEST_VIDEO_PATH = "/data/local/tmp/blue_eyes.mp4"

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
    var videoBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var asciiText by remember { mutableStateOf("") }
    var asciiColors by remember { mutableStateOf<IntArray?>(null) }
    var showVideo by remember { mutableStateOf(true) }
    var frameCount by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        val player = ExoPlayer.Builder(context).build()
        exoPlayer = player

        // Try to load test video file
        val videoUri = Uri.parse("file://$TEST_VIDEO_PATH")
        val mediaItem = MediaItem.fromUri(videoUri)
        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()

        Log.d(TAG, "ExoPlayer initialized with video: $TEST_VIDEO_PATH")

        onDispose {
            player.release()
            exoPlayer = null
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Player toggle buttons
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Button(
                onClick = { showVideo = !showVideo },
                modifier = Modifier.align(Alignment.Center)
            ) {
                Text(if (showVideo) "Show ASCII" else "Show Video")
            }
        }

        // Display content based on toggle
        if (showVideo) {
            // Video display
            exoPlayer?.let {
                AndroidView(
                    factory = { context ->
                        StyledPlayerView(context).apply {
                            player = it
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

        // Playback info
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Text(
                "Frames processed: $frameCount",
                modifier = Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}
