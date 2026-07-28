"""
Image processing module for ASCII art generation.
Handles grayscale conversion, rotation, scaling, and contrast adjustment.
"""

import numpy as np
from PIL import Image
import logging

logger = logging.getLogger(__name__)


class ImageProcessor:
    """Processes camera frames for ASCII art generation."""
    
    # ASCII characters sorted by visual density (light to dark)
    ASCII_CHARS = ' .:-=+*#%@'
    
    def __init__(self, scale_factor=8, contrast_factor=1.0):
        """
        Initialize image processor.
        
        Args:
            scale_factor: Downscale factor (8 = 8x smaller)
            contrast_factor: Contrast multiplier (1.0 = normal)
        """
        self.scale_factor = scale_factor
        self.contrast_factor = contrast_factor
    
    def process_yuv420(self, frame_data, width, height):
        """
        Convert YUV420 frame data to RGB.
        
        YUV420 layout:
        - Y plane: Full resolution
        - U plane: 1/4 resolution (quarter size)
        - V plane: 1/4 resolution (quarter size)
        
        Args:
            frame_data: Numpy array of YUV420 data
            width: Frame width in pixels
            height: Frame height in pixels
            
        Returns:
            RGB numpy array (height, width, 3)
        """
        # Extract Y, U, V planes from YUV420 data
        y_size = width * height
        u_size = (width // 2) * (height // 2)
        
        y_plane = frame_data[:y_size].reshape((height, width))
        u_plane = frame_data[y_size:y_size + u_size].reshape((height // 2, width // 2))
        v_plane = frame_data[y_size + u_size:y_size + u_size + u_size].reshape((height // 2, width // 2))
        
        # Upsample U and V planes to full resolution
        u_full = np.repeat(np.repeat(u_plane, 2, axis=0), 2, axis=1)
        v_full = np.repeat(np.repeat(v_plane, 2, axis=0), 2, axis=1)
        
        # YUV to RGB conversion
        # R = Y + 1.402 * (V - 128)
        # G = Y - 0.344 * (U - 128) - 0.714 * (V - 128)
        # B = Y + 1.772 * (U - 128)
        r = np.clip(y_plane + 1.402 * (v_full - 128), 0, 255).astype(np.uint8)
        g = np.clip(y_plane - 0.344 * (u_full - 128) - 0.714 * (v_full - 128), 0, 255).astype(np.uint8)
        b = np.clip(y_plane + 1.772 * (u_full - 128), 0, 255).astype(np.uint8)
        
        return np.stack([r, g, b], axis=2)
    
    def to_grayscale(self, rgb_frame):
        """
        Convert RGB frame to grayscale using standard luminance formula.
        
        Args:
            rgb_frame: RGB numpy array (height, width, 3)
            
        Returns:
            Grayscale numpy array (height, width) with values 0-255
        """
        # Standard luminance formula: 0.299*R + 0.587*G + 0.114*B
        gray = (0.299 * rgb_frame[:, :, 0] + 
                0.587 * rgb_frame[:, :, 1] + 
                0.114 * rgb_frame[:, :, 2]).astype(np.uint8)
        return gray
    
    def rotate_if_needed(self, frame, rotation_degrees=90):
        """
        Rotate frame if camera sensor requires it.
        
        Raspberry Pi Camera Module 2 is typically mounted at 90 degrees.
        
        Args:
            frame: Numpy array (height, width, ...)
            rotation_degrees: Rotation in degrees (90, 180, 270)
            
        Returns:
            Rotated frame
        """
        if rotation_degrees == 0:
            return frame
        elif rotation_degrees == 90:
            return np.rot90(frame, k=3)  # Rotate -90 degrees (right)
        elif rotation_degrees == 180:
            return np.rot90(frame, k=2)
        elif rotation_degrees == 270:
            return np.rot90(frame, k=1)  # Rotate +90 degrees (left)
        else:
            logger.warning(f"Unsupported rotation: {rotation_degrees}")
            return frame
    
    def scale_down(self, frame):
        """
        Scale down frame for faster ASCII processing.
        
        Args:
            frame: Numpy array (height, width)
            
        Returns:
            Scaled numpy array
        """
        new_height = max(1, frame.shape[0] // self.scale_factor)
        new_width = max(1, frame.shape[1] // self.scale_factor)
        
        # Use PIL for quality downsampling
        pil_image = Image.fromarray(frame)
        pil_image = pil_image.resize((new_width, new_height), Image.Resampling.LANCZOS)
        return np.array(pil_image)
    
    def adjust_contrast(self, frame):
        """
        Adjust contrast of grayscale frame.
        
        Args:
            frame: Grayscale numpy array (0-255)
            
        Returns:
            Contrast-adjusted array
        """
        if self.contrast_factor == 1.0:
            return frame
        
        # Center around 128, scale by contrast factor, clip to 0-255
        centered = frame.astype(float) - 128
        adjusted = centered * self.contrast_factor + 128
        return np.clip(adjusted, 0, 255).astype(np.uint8)
    
    def process_frame(self, frame_data, width, height, rotation_degrees=90):
        """
        Full processing pipeline: YUV420 -> RGB -> Grayscale -> Rotate -> Scale -> Contrast.
        
        NOTE: Rotation is applied after grayscale but before scaling for efficiency.
        
        Args:
            frame_data: YUV420 numpy array (flattened 1D)
            width: Original frame width in pixels
            height: Original frame height in pixels
            rotation_degrees: Camera rotation (0, 90, 180, 270)
            
        Returns:
            Processed grayscale numpy array ready for ASCII conversion
        """
        try:
            # Validate frame size
            expected_size = width * height + (width * height) // 2  # Y + U + V
            if frame_data.size != expected_size:
                logger.warning(f"Frame size mismatch: expected {expected_size}, got {frame_data.size}")
                # Try to handle anyway
            
            # YUV420 -> RGB
            rgb = self.process_yuv420(frame_data, width, height)
            
            # RGB -> Grayscale
            gray = self.to_grayscale(rgb)
            
            # Apply rotation (if needed for camera orientation)
            # Must rotate before scaling to maintain aspect ratio
            rotated = self.rotate_if_needed(gray, rotation_degrees)
            
            # Scale down for ASCII processing
            scaled = self.scale_down(rotated)
            
            # Adjust contrast
            adjusted = self.adjust_contrast(scaled)
            
            return adjusted
            
        except Exception as e:
            logger.error(f"Error in process_frame: {e}")
            raise
    
    def get_processed_dimensions(self, original_height, original_width, rotation_degrees=90):
        """
        Calculate processed frame dimensions after rotation and scaling.
        
        Args:
            original_height: Original height before rotation
            original_width: Original width before rotation
            rotation_degrees: Rotation in degrees
            
        Returns:
            Tuple of (output_height, output_width) after all processing
        """
        # After rotation, dimensions may swap
        if rotation_degrees in [90, 270]:
            rotated_height = original_width
            rotated_width = original_height
        else:
            rotated_height = original_height
            rotated_width = original_width
        
        # After scaling
        scaled_height = max(1, rotated_height // self.scale_factor)
        scaled_width = max(1, rotated_width // self.scale_factor)
        
        return scaled_height, scaled_width
