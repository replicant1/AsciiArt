package com.rodbailey.asciiart.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.rodbailey.asciiart.R
import com.rodbailey.asciiart.processing.AsciiDisplayMode
import com.rodbailey.asciiart.processing.ExoPlayerFrameListener

private const val TAG = "VideoFilePlayer"

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
    var loadedVideoUri by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var videoBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var asciiText by remember { mutableStateOf("") }
    var asciiColors by remember { mutableStateOf<IntArray?>(null) }

    val currentScaleFactor = rememberUpdatedState(scaleFactor)
    val currentContrastFactor = rememberUpdatedState(contrastFactor)
    val currentColorEnabled = rememberUpdatedState(colorEnabled)
    val currentDisplayMode = rememberUpdatedState(displayMode)
    val currentCaptureTextureView = rememberUpdatedState(captureTextureView)
    val currentBitmapSetter = rememberUpdatedState { bitmap: Bitmap? -> videoBitmap = bitmap }
    val currentTextSetter = rememberUpdatedState { text: String -> asciiText = text }
    val currentColorsSetter = rememberUpdatedState { colors: IntArray? -> asciiColors = colors }

    // File picker — opens in the Documents directory
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = object : ActivityResultContracts.OpenDocument() {
            override fun createIntent(context: android.content.Context, input: Array<String>): Intent {
                return super.createIntent(context, input).apply {
                    val docsUri = DocumentsContract.buildDocumentUri(
                        "com.android.externalstorage.documents",
                        "primary:Download"
                    )
                    putExtra(DocumentsContract.EXTRA_INITIAL_URI, docsUri)
                }
            }
        }
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not persist URI permission", e)
            }
            loadedVideoUri = uri.toString()
        }
    }

    // Create and tear down ExoPlayer + frame listener
    DisposableEffect(Unit) {
        val player = ExoPlayer.Builder(context).build()
        exoPlayer = player

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
            }
        )
        frameListener = listener
        listener.startListening()

        onDispose {
            listener.release()
            player.release()
            exoPlayer = null
            frameListener = null
        }
    }

    // Track ExoPlayer's playing state for the Play/Pause button
    DisposableEffect(exoPlayer) {
        val player = exoPlayer ?: return@DisposableEffect onDispose {}
        val playbackListener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
        }
        player.addListener(playbackListener)
        onDispose { player.removeListener(playbackListener) }
    }

    // Load and play video when URI changes
    LaunchedEffect(loadedVideoUri, exoPlayer) {
        val player = exoPlayer ?: return@LaunchedEffect
        val uri = loadedVideoUri ?: return@LaunchedEffect
        player.setMediaItem(MediaItem.fromUri(Uri.parse(uri)))
        player.prepare()
        player.play()
    }

    // Point ExoPlayer at the capture TextureView. Both display modes render from captured
    // frames, so the target never changes with displayMode — but it does have to wait for
    // the TextureView, which AndroidView creates after the first composition. Keying on
    // captureTextureView makes that ordering irrelevant.
    LaunchedEffect(exoPlayer, captureTextureView) {
        val player = exoPlayer ?: return@LaunchedEffect
        val textureView = captureTextureView ?: return@LaunchedEffect
        player.setVideoTextureView(textureView)
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Persistent control bar — visible in both IMAGE and ASCII modes
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = { videoPickerLauncher.launch(arrayOf("video/*")) }) {
                // The icon is decorative — the adjacent label already names the action.
                Icon(Icons.Default.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.video_file_load_button),
                    style = MaterialTheme.typography.labelLarge
                )
            }
            IconButton(
                onClick = { exoPlayer?.seekTo(0); exoPlayer?.play() },
                enabled = loadedVideoUri != null
            ) {
                Icon(
                    Icons.Default.SkipPrevious,
                    contentDescription = stringResource(R.string.video_file_restart_button)
                )
            }
            IconButton(
                onClick = { exoPlayer?.play() },
                enabled = loadedVideoUri != null && !isPlaying
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.video_file_play_button)
                )
            }
            IconButton(
                onClick = { exoPlayer?.pause() },
                enabled = loadedVideoUri != null && isPlaying
            ) {
                Icon(
                    Icons.Default.Pause,
                    contentDescription = stringResource(R.string.video_file_pause_button)
                )
            }
        }

        // Content area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            // Hidden capture surface — ExoPlayer renders the decoded video here in both
            // display modes and getBitmap() reads it back. alpha=0 keeps it invisible;
            // the processed output is drawn over the top.
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply { alpha = 0f }.also { captureTextureView = it }
                },
                modifier = Modifier.fillMaxSize()
            )

            if (loadedVideoUri == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.video_file_none_loaded),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                val bitmap = videoBitmap
                if (bitmap != null) {
                    when (displayMode) {
                        AsciiDisplayMode.IMAGE -> ImagePreview(
                            bitmap = bitmap,
                            colorEnabled = colorEnabled,
                            asciiColors = asciiColors,
                            modifier = Modifier.fillMaxSize()
                        )

                        AsciiDisplayMode.ASCII -> AsciiGridPreview(
                            bitmap = bitmap,
                            asciiText = asciiText,
                            asciiColors = asciiColors,
                            colorEnabled = colorEnabled,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            stringResource(R.string.video_file_waiting_for_frames),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}


