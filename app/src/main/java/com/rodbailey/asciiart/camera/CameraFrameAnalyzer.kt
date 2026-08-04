package com.rodbailey.asciiart.camera

import android.os.Handler
import android.os.Looper
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.rodbailey.asciiart.processing.AsciiDisplayMode
import com.rodbailey.asciiart.processing.FrameProcessingResult
import com.rodbailey.asciiart.processing.ImageProcessor

class CameraFrameAnalyzer(
    private val scaleFactorProvider: () -> Int,
    private val contrastFactorProvider: () -> Float,
    private val colorEnabledProvider: () -> Boolean,
    private val displayModeProvider: () -> AsciiDisplayMode,
    private val onFrameProcessed: (frame: FrameProcessingResult) -> Unit
) : ImageAnalysis.Analyzer {

    private val mainThreadHandler = Handler(Looper.getMainLooper())

    /**
     * Processes one camera frame into an oriented de-res grid and posts it to the UI.
     *
     * `imageInfo.rotationDegrees` is passed straight through to [ImageProcessor], which
     * applies it while downsampling. Rotation is needed even though the app is locked to
     * portrait, because camera sensors are physically mounted at a fixed angle — typically
     * 90 degrees on phones like the Pixel 3 — so without it the output renders sideways.
     *
     * The ASCII conversion happens inside [ImageProcessor] rather than here, because the
     * grayscale pixels it reads live in a buffer that is reused by the next frame. Keeping
     * them from crossing to the main thread is what lets that buffer stay reusable.
     */
    override fun analyze(image: ImageProxy) {
        val frameResult = ImageProcessor.processLumaFrame(
            image = image,
            scaleFactor = scaleFactorProvider(),
            contrastFactor = contrastFactorProvider(),
            colorEnabled = colorEnabledProvider(),
            displayMode = displayModeProvider(),
            rotationDegrees = image.imageInfo.rotationDegrees
        )
        image.close()
        mainThreadHandler.post { onFrameProcessed(frameResult) }
    }
}
