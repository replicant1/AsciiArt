#!/usr/bin/env python3
"""
ASCII Art Live Camera Preview for Raspberry Pi Zero 2
Captures video from Camera Module 2 and displays it as ASCII art on HDMI screen.
"""

import logging
import sys
import time
from pathlib import Path

# Add src directory to path
sys.path.insert(0, str(Path(__file__).parent / 'src'))

from camera import CameraCapture
from image_processor import ImageProcessor
from ascii_art import AsciiArt
from display import NcursesDisplay


# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)


class AsciiArtLiveCamera:
    """Main application for ASCII art live camera preview."""
    
    def __init__(self, 
                 camera_resolution=(640, 480),
                 frame_rate=30,
                 scale_factor=8,
                 contrast=1.0,
                 camera_rotation=90):
        """
        Initialize ASCII art camera application.
        
        Args:
            camera_resolution: Tuple of (width, height) for camera
            frame_rate: Target frame rate in Hz
            scale_factor: Downscale factor for ASCII processing (8 = 8x smaller)
            contrast: Contrast adjustment (1.0 = normal)
            camera_rotation: Camera rotation in degrees (90 typical for Pi Camera)
        """
        self.camera_resolution = camera_resolution
        self.frame_rate = frame_rate
        self.scale_factor = scale_factor
        self.contrast = contrast
        self.camera_rotation = camera_rotation
        
        self.camera = None
        self.image_processor = None
        self.ascii_art = None
        self.display = None
        self.is_running = False
        self.frame_count = 0
        self.start_time = None
    
    def initialize(self):
        """Initialize all components."""
        logger.info("Initializing ASCII Art Live Camera")
        
        # Initialize camera
        self.camera = CameraCapture(
            resolution=self.camera_resolution,
            frame_rate=self.frame_rate
        )
        
        # Initialize image processor
        self.image_processor = ImageProcessor(
            scale_factor=self.scale_factor,
            contrast_factor=self.contrast
        )
        
        # Initialize ASCII art generator
        self.ascii_art = AsciiArt()
        
        # Initialize display
        self.display = NcursesDisplay(enable_color=False)
        
        # Start components
        self.camera.start()
        self.display.start()
        
        self.is_running = True
        self.start_time = time.time()
        logger.info("Initialization complete")
    
    def run(self):
        """Main event loop: capture -> process -> display."""
        logger.info("Starting ASCII art live preview")
        
        try:
            while self.is_running:
                # Capture frame from camera
                frame_data = self.camera.get_frame(timeout=0.1)
                if frame_data is None:
                    time.sleep(0.01)
                    continue
                
                # Process frame: YUV420 -> Grayscale -> Scaled
                width, height = self.camera_resolution
                try:
                    processed = self.image_processor.process_frame(
                        frame_data,
                        width, height,
                        rotation_degrees=self.camera_rotation
                    )
                except Exception as e:
                    logger.error(f"Error processing frame: {e}")
                    continue
                
                # Verify we got a valid processed frame
                if processed is None or processed.size == 0:
                    logger.warning("Processed frame is empty")
                    continue
                
                # Convert to ASCII art
                try:
                    ascii_lines = self.ascii_art.to_ascii_text(processed)
                except Exception as e:
                    logger.error(f"Error generating ASCII art: {e}")
                    continue
                
                # Display on terminal
                try:
                    self.display.render(ascii_lines)
                except Exception as e:
                    logger.error(f"Error rendering display: {e}")
                    continue
                
                # Check for user input (quit)
                key = self.display.get_key()
                if key and key.lower() == 'q':
                    logger.info("User quit")
                    self.is_running = False
                
                # Track frame statistics
                self.frame_count += 1
                if self.frame_count % 30 == 0:  # Log every 30 frames
                    elapsed = time.time() - self.start_time
                    actual_fps = self.frame_count / elapsed if elapsed > 0 else 0
                    logger.info(f"Frames: {self.frame_count} | FPS: {actual_fps:.1f}")
        
        except KeyboardInterrupt:
            logger.info("Interrupted by user")
            self.is_running = False
        
        except Exception as e:
            logger.error(f"Error in main loop: {e}")
            self.is_running = False
        
        finally:
            self.cleanup()
    
    def cleanup(self):
        """Clean up and shutdown."""
        logger.info("Shutting down")
        
        if self.camera:
            try:
                self.camera.stop()
            except Exception as e:
                logger.error(f"Error stopping camera: {e}")
        
        if self.display:
            try:
                self.display.cleanup()
            except Exception as e:
                logger.error(f"Error cleaning up display: {e}")
        
        elapsed = time.time() - self.start_time if self.start_time else 0
        avg_fps = self.frame_count / elapsed if elapsed > 0 else 0
        logger.info(f"Shutdown complete - Processed {self.frame_count} frames in {elapsed:.1f}s ({avg_fps:.1f} avg FPS)")


def main():
    """Entry point."""
    import argparse
    
    parser = argparse.ArgumentParser(
        description='ASCII Art Live Camera Preview for Raspberry Pi'
    )
    parser.add_argument(
        '--scale', type=int, default=8,
        help='Scale factor for ASCII grid (default: 8)'
    )
    parser.add_argument(
        '--contrast', type=float, default=1.0,
        help='Contrast adjustment (default: 1.0)'
    )
    parser.add_argument(
        '--fps', type=int, default=30,
        help='Target frame rate (default: 30)'
    )
    parser.add_argument(
        '--rotation', type=int, default=90,
        help='Camera rotation in degrees (default: 90)'
    )
    parser.add_argument(
        '--verbose', action='store_true',
        help='Enable verbose logging'
    )
    
    args = parser.parse_args()
    
    # Set log level
    if args.verbose:
        logging.getLogger().setLevel(logging.DEBUG)
    
    # Create and run application
    app = AsciiArtLiveCamera(
        camera_resolution=(640, 480),
        frame_rate=args.fps,
        scale_factor=args.scale,
        contrast=args.contrast,
        camera_rotation=args.rotation
    )
    
    try:
        app.initialize()
        app.run()
    except Exception as e:
        logger.error(f"Fatal error: {e}", exc_info=True)
        app.cleanup()
        return 1
    
    return 0


if __name__ == '__main__':
    sys.exit(main())
