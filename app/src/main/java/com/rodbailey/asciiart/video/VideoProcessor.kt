package com.rodbailey.asciiart.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log

data class VideoFrame(
    val bitmap: Bitmap,
    val frameNumber: Int,
    val presentationTimeUs: Long
)

class VideoFrameExtractor(private val context: Context) {
    private var retriever: MediaMetadataRetriever? = null
    private var totalFrames: Int = 0
    private var videoWidth: Int = 0
    private var videoHeight: Int = 0
    private var frameRateNum: Int = 24  // Default 24 fps
    private var videoRotation: Int = 0

    fun initialize(videoUri: Uri): Boolean {
        return try {
            retriever = MediaMetadataRetriever()
            retriever?.setDataSource(context, videoUri)

            val widthStr = retriever?.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val heightStr = retriever?.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val rotationStr = retriever?.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            val durationStr = retriever?.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val fpsStr = retriever?.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)

            videoWidth = widthStr?.toIntOrNull() ?: 1920
            videoHeight = heightStr?.toIntOrNull() ?: 1080
            videoRotation = rotationStr?.toIntOrNull() ?: 0
            val durationMs = durationStr?.toLongOrNull() ?: 0L
            frameRateNum = fpsStr?.toFloatOrNull()?.toInt() ?: 24

            totalFrames = if (frameRateNum > 0) {
                ((durationMs / 1000.0) * frameRateNum).toInt()
            } else {
                0
            }

            Log.d(TAG, "Video initialized: ${videoWidth}x${videoHeight} rotation=$videoRotation @ ${frameRateNum}fps, total frames: $totalFrames")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize video", e)
            false
        }
    }

    fun getFrameAtIndex(frameIndex: Int): Bitmap? {
        return try {
            if (frameIndex < 0 || frameIndex >= totalFrames) {
                return null
            }
            val timeUs = (frameIndex * 1_000_000L) / frameRateNum.coerceAtLeast(1)
            retriever?.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract frame at index $frameIndex", e)
            null
        }
    }

    fun getTotalFrames(): Int = totalFrames

    fun getVideoWidth(): Int = videoWidth

    fun getVideoHeight(): Int = videoHeight

    fun getVideoRotation(): Int = videoRotation

    fun getFrameRateFps(): Int = frameRateNum

    fun release() {
        try {
            retriever?.release()
            retriever = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing retriever", e)
        }
    }

    companion object {
        private const val TAG = "VideoFrameExtractor"
    }
}

class VideoProcessor(private val context: Context) {
    private var extractor: VideoFrameExtractor? = null
    private var currentFrameIndex = 0
    private var isPlaying = false

    fun loadVideo(videoUri: Uri): Boolean {
        release()
        extractor = VideoFrameExtractor(context)
        val success = extractor?.initialize(videoUri) ?: false
        if (success) {
            currentFrameIndex = 0
            isPlaying = true
        }
        return success
    }

    fun getNextFrame(): Bitmap? {
        if (!isPlaying || extractor == null) {
            return null
        }

        val bitmap = extractor?.getFrameAtIndex(currentFrameIndex)
        if (bitmap != null) {
            currentFrameIndex++
            if (currentFrameIndex >= (extractor?.getTotalFrames() ?: 0)) {
                currentFrameIndex = 0  // Loop back to start
            }
        }
        return bitmap
    }

    fun getFrameAtIndex(index: Int): Bitmap? {
        return extractor?.getFrameAtIndex(index)
    }

    fun setFrameIndex(index: Int) {
        currentFrameIndex = index.coerceIn(0, (extractor?.getTotalFrames() ?: 1) - 1)
    }

    fun play() {
        isPlaying = true
    }

    fun pause() {
        isPlaying = false
    }

    fun stop() {
        isPlaying = false
        currentFrameIndex = 0
    }

    fun getTotalFrames(): Int = extractor?.getTotalFrames() ?: 0

    fun getCurrentFrameIndex(): Int = currentFrameIndex

    fun getVideoWidth(): Int = extractor?.getVideoWidth() ?: 0

    fun getVideoHeight(): Int = extractor?.getVideoHeight() ?: 0

    fun getVideoRotation(): Int = extractor?.getVideoRotation() ?: 0

    fun getFrameRate(): Int = extractor?.getFrameRateFps() ?: 24

    fun isVideoLoaded(): Boolean = extractor != null

    fun isPlaying(): Boolean = isPlaying

    fun release() {
        extractor?.release()
        extractor = null
        isPlaying = false
        currentFrameIndex = 0
    }

    companion object {
        private const val TAG = "VideoProcessor"
    }
}
