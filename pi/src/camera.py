"""
Camera capture module for Raspberry Pi Camera Module 2.
Handles frame capture and basic YUV data extraction.
"""

from picamera2 import Picamera2
import numpy as np
from threading import Thread
from queue import Queue
import logging

logger = logging.getLogger(__name__)


class CameraCapture:
    """Captures frames from Raspberry Pi Camera Module 2."""
    
    def __init__(self, resolution=(640, 480), frame_rate=30):
        """
        Initialize camera capture.
        
        Args:
            resolution: Tuple of (width, height)
            frame_rate: Target frame rate in Hz
        """
        self.resolution = resolution
        self.frame_rate = frame_rate
        self.picam2 = None
        self.frame_queue = Queue(maxsize=3)  # Keep 3 frames max
        self.is_running = False
        self.capture_thread = None
        
    def start(self):
        """Start camera capture thread."""
        if self.is_running:
            return
            
        try:
            self.picam2 = Picamera2()
            
            # Configure camera for YUV420 format (standard video format)
            config = self.picam2.create_video_configuration(
                main={"format": "YUV420", "size": self.resolution},
                controls={"FrameRate": self.frame_rate}
            )
            self.picam2.configure(config)
            self.picam2.start()
            
            self.is_running = True
            self.capture_thread = Thread(target=self._capture_loop, daemon=True)
            self.capture_thread.start()
            logger.info(f"Camera started: {self.resolution} @ {self.frame_rate}fps")
            
        except Exception as e:
            logger.error(f"Failed to start camera: {e}")
            raise
    
    def _capture_loop(self):
        """Continuously capture frames and queue them."""
        while self.is_running:
            try:
                # Capture frame as array
                frame = self.picam2.capture_array()
                
                # Drop oldest frame if queue is full (non-blocking)
                if self.frame_queue.full():
                    try:
                        self.frame_queue.get_nowait()
                    except:
                        pass
                
                self.frame_queue.put(frame)
                
            except Exception as e:
                logger.error(f"Error capturing frame: {e}")
    
    def get_frame(self, timeout=0.1):
        """
        Get the latest captured frame.
        
        Args:
            timeout: Timeout in seconds to wait for frame
            
        Returns:
            Numpy array of frame data, or None if timeout
        """
        try:
            return self.frame_queue.get(timeout=timeout)
        except:
            return None
    
    def stop(self):
        """Stop camera capture."""
        self.is_running = False
        if self.capture_thread:
            self.capture_thread.join(timeout=2)
        if self.picam2:
            self.picam2.stop()
            self.picam2.close()
        logger.info("Camera stopped")
