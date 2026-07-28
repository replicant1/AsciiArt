package com.rodbailey.asciiart.processing

import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class FrameQueueStateTest {
    
    private lateinit var state: FrameQueueState

    @Before
    fun setUp() {
        state = FrameQueueState()
    }

    @Test
    fun detectPlaybackRestart_jumpBackMoreThan1Second_resetsState() {
        // Arrange: Record a frame at 5000ms
        state.recordQueuedFrame(5000)
        state.recordProcessedFrame(5000)
        
        // Act: Jump back to 3000ms (2 second jump)
        val restarted = state.detectPlaybackRestart(3000)
        
        // Assert
        assertTrue("Should detect playback restart", restarted)
        // After restart, shouldQueueFrame should work from fresh state
        assertTrue("Should queue frame after restart", state.shouldQueueFrame(3100, skipRate = 1))
    }

    @Test
    fun detectPlaybackRestart_noJumpBack_returnsFalse() {
        // Arrange
        state.recordQueuedFrame(1000)
        
        // Act
        val restarted = state.detectPlaybackRestart(1500)
        
        // Assert
        assertFalse("Should not detect restart with forward position", restarted)
    }

    @Test
    fun detectPlaybackRestart_exactlyAtBoundary_noRestart() {
        // Arrange: 1000ms boundary means jump must be > 1000ms
        state.recordQueuedFrame(2000)
        
        // Act: Jump back exactly 1000ms (to 1000)
        val restarted = state.detectPlaybackRestart(1000)
        
        // Assert: Should NOT restart (needs to be MORE than 1000ms back)
        assertFalse("Should not restart at exactly 1000ms boundary", restarted)
    }

    @Test
    fun shouldQueueFrame_insufficientTimeElapsed_returnsFalse() {
        // Arrange
        state.recordQueuedFrame(1000)
        
        // Act: Try to queue when only 10ms has passed (need 30ms)
        val shouldQueue = state.shouldQueueFrame(1010, skipRate = 1)
        
        // Assert
        assertFalse("Should not queue when < 30ms elapsed", shouldQueue)
    }

    @Test
    fun shouldQueueFrame_30msExactly_returnsFalse() {
        // Arrange: Recorded at 1000ms
        state.recordQueuedFrame(1000)
        
        // Act: Exactly 30ms later
        val shouldQueue = state.shouldQueueFrame(1030, skipRate = 1)
        
        // Assert: Should be false because condition is > 30, not >= 30
        assertFalse("Should not queue at exactly 30ms", shouldQueue)
    }

    @Test
    fun shouldQueueFrame_31msElapsed_returnsTrue() {
        // Arrange
        state.recordQueuedFrame(1000)
        
        // Act: 31ms elapsed
        val shouldQueue = state.shouldQueueFrame(1031, skipRate = 1)
        
        // Assert
        assertTrue("Should queue when > 30ms elapsed", shouldQueue)
    }

    @Test
    fun shouldQueueFrame_skipRateOfTwo_queuesEverySecondOpportunity() {
        // Arrange: skipRate = 2 means queue every 2nd opportunity
        
        // Act & Assert: Queue at frame 1
        assertFalse("Skip 1st frame", state.shouldQueueFrame(100, skipRate = 2))
        state.recordQueuedFrame(100)

        // Queue at frame 2
        assertTrue("Queue 2nd frame", state.shouldQueueFrame(200, skipRate = 2))
        state.recordQueuedFrame(200)

        // Skip frame 3
        assertFalse("Skip 3rd frame", state.shouldQueueFrame(300, skipRate = 2))
        state.recordQueuedFrame(300)

        // Queue frame 4
        assertTrue("Queue 4th frame", state.shouldQueueFrame(400, skipRate = 2))
        state.recordQueuedFrame(400)
    }

    @Test
    fun shouldQueueFrame_skipRateOfThree_queuesEveryThirdOpportunity() {
        // Arrange: skipRate = 3
        
        // Skip 1st and 2nd
        assertFalse(state.shouldQueueFrame(100, skipRate = 3))
        state.recordQueuedFrame(100)
        assertFalse(state.shouldQueueFrame(200, skipRate = 3))
        state.recordQueuedFrame(200)

        // Queue 3rd
        assertTrue(state.shouldQueueFrame(300, skipRate = 3))
        state.recordQueuedFrame(300)

        // Skip 4th and 5th
        assertFalse(state.shouldQueueFrame(400, skipRate = 3))
        state.recordQueuedFrame(400)
        assertFalse(state.shouldQueueFrame(500, skipRate = 3))
        state.recordQueuedFrame(500)

        // Queue 6th
        assertTrue(state.shouldQueueFrame(600, skipRate = 3))
    }

    @Test
    fun shouldProcessFrame_sameFrame_returnsTrue() {
        // Arrange: Record processing of frame at 1000ms
        state.recordProcessedFrame(1000)
        
        // Act: Try to process same frame again (for parameter changes)
        val shouldProcess = state.shouldProcessFrame(1000)
        
        // Assert
        assertTrue("Should allow re-processing of same frame", shouldProcess)
    }

    @Test
    fun shouldProcessFrame_newFrameTooSoon_returnsFalse() {
        // Arrange
        state.recordProcessedFrame(1000)
        
        // Act: Only 40ms passed (need 50ms for new frames)
        val shouldProcess = state.shouldProcessFrame(1040)
        
        // Assert
        assertFalse("Should not process new frame < 50ms after last", shouldProcess)
    }

    @Test
    fun shouldProcessFrame_newFrameAt50msExactly_returnsFalse() {
        // Arrange
        state.recordProcessedFrame(1000)
        
        // Act: Exactly 50ms elapsed (condition is > 50, not >= 50)
        val shouldProcess = state.shouldProcessFrame(1050)
        
        // Assert
        assertFalse("Should not process at exactly 50ms", shouldProcess)
    }

    @Test
    fun shouldProcessFrame_newFrameAfter50ms_returnsTrue() {
        // Arrange
        state.recordProcessedFrame(1000)
        
        // Act: 51ms elapsed
        val shouldProcess = state.shouldProcessFrame(1051)
        
        // Assert
        assertTrue("Should process new frame > 50ms after last", shouldProcess)
    }

    @Test
    fun shouldProcessFrame_firstFrame_returnsTrue() {
        // Arrange: No frame processed yet (lastProcessedTimeMs = 0)
        
        // Act: Request processing of frame at 1000ms
        val shouldProcess = state.shouldProcessFrame(1000)
        
        // Assert
        assertTrue("Should process first frame", shouldProcess)
    }

    @Test
    fun recordQueuedFrame_updatesState() {
        // Arrange
        state.shouldQueueFrame(100, skipRate = 1)  // Increments counter
        
        // Act
        state.recordQueuedFrame(100)
        
        // Assert: Now 30ms+ later, shouldQueueFrame should work
        assertTrue(state.shouldQueueFrame(131, skipRate = 1))
    }

    @Test
    fun recordProcessedFrame_updatesState() {
        // Arrange
        state.recordProcessedFrame(1000)
        
        // Act: Verify state was updated
        val shouldProcess = state.shouldProcessFrame(1050)  // Not enough delay yet
        
        // Assert
        assertFalse("recordProcessedFrame should update processing timestamp", shouldProcess)
    }

    @Test
    fun integrationTest_fullFrameLifecycle() {
        // Test a realistic scenario: Queue frames, detect restart, re-queue
        
        // Initial frame opportunities at skipRate=2 starting at higher timestamp
        assertFalse(state.shouldQueueFrame(5000, skipRate = 2))  // Skip
        state.recordQueuedFrame(5000)
        assertTrue(state.shouldQueueFrame(5200, skipRate = 2))   // Queue
        state.recordQueuedFrame(5200)
        state.recordProcessedFrame(5200)
        
        // User seeks back by 3 seconds (5200 - 3000 = 2200)
        assertTrue(state.detectPlaybackRestart(2200))  // Jump back detected
        
        // Fresh start: Skip, then queue
        assertFalse(state.shouldQueueFrame(2300, skipRate = 2))
        state.recordQueuedFrame(2300)
        assertTrue(state.shouldQueueFrame(2400, skipRate = 2))
        state.recordQueuedFrame(2400)
        state.recordProcessedFrame(2400)
        
        // Normal processing continues
        assertFalse(state.shouldQueueFrame(2500, skipRate = 2))  // Skip pattern resumes
    }
}
