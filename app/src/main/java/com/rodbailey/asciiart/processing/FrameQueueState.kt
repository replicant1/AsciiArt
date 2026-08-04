package com.rodbailey.asciiart.processing

/**
 * Encapsulates the state tracking for frame queueing and processing.
 * Keeps frame timing logic separate and testable.
 */
class FrameQueueState {
    /**
     * Last playback position we queued for extraction.
     * Used to ensure minimum 30ms between frame extraction requests.
     */
    private var lastQueuedTimeMs = 0L

    /**
     * Counter for frame skip implementation.
     * Incremented for each frame opportunity; only process when counter % frameSkipRate == 0
     */
    private var frameSkipCounter = 0

    /**
     * Detects when playback has jumped backward (user seeked or video looped).
     * When detected, resets all tracking state to start fresh.
     *
     * @return true if restart was detected and state was reset
     */
    fun detectPlaybackRestart(currentTimeMs: Long): Boolean {
        // If position jumped back by >1 second, it's a restart
        if (currentTimeMs < lastQueuedTimeMs - 1000) {
            lastQueuedTimeMs = 0
            frameSkipCounter = 0
            return true
        }
        return false
    }

    /**
     * Determines if the current playback position warrants queuing a frame for extraction.
     *
     * Logic:
     * 1. Position must have advanced by at least 30ms since last queued frame
     * 2. Frame skip counter must be at the right value (e.g., every 2nd opportunity)
     *
     * @param currentTimeMs playback position
     * @param skipRate process every Nth frame opportunity (e.g., 2 = process every 2nd)
     * @return true if frame should be queued
     */
    fun shouldQueueFrame(currentTimeMs: Long, skipRate: Int): Boolean {
        // Check if enough time has passed since last queued frame (30ms minimum)
        if (currentTimeMs <= lastQueuedTimeMs + 30) {
            return false
        }

        // Increment skip counter and check if we should process this one
        frameSkipCounter++
        return frameSkipCounter % skipRate == 0
    }

    /**
     * Records that a frame time has been queued for extraction.
     * Called after successfully sending frame time to the queue.
     */
    fun recordQueuedFrame(frameTimeMs: Long) {
        lastQueuedTimeMs = frameTimeMs
    }

}
