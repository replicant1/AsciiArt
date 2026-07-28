"""
ASCII art generation module.
Converts grayscale images to ASCII text based on character density.
"""

import numpy as np
from collections import defaultdict
import logging

logger = logging.getLogger(__name__)


class AsciiArt:
    """Generates ASCII art from grayscale images."""
    
    # ASCII characters sorted by visual density (light to dark)
    ASCII_CHARS = ' .:-=+*#%@'
    
    def __init__(self):
        """Initialize ASCII art generator."""
        self._density_cache = {}
        self._build_character_set()
    
    def _build_character_set(self):
        """Build and cache character density values."""
        """
        Calculate visual density for each ASCII character by rendering it
        as a small bitmap and measuring the pixel coverage.
        """
        for char in self.ASCII_CHARS:
            # Simple heuristic: character position in string correlates to density
            # In a real implementation, you'd render each character and measure pixels
            density = self.ASCII_CHARS.index(char) / len(self.ASCII_CHARS)
            self._density_cache[char] = density
        
        logger.debug(f"Character density cache built: {self._density_cache}")
    
    def _pixel_to_char(self, pixel_value):
        """
        Map a grayscale pixel value (0-255) to an ASCII character.
        
        Args:
            pixel_value: Grayscale value (0=dark, 255=light)
            
        Returns:
            ASCII character matching brightness
        """
        # Normalize pixel value to 0-1 range
        normalized = pixel_value / 255.0
        
        # Map to character index
        char_index = int(normalized * (len(self.ASCII_CHARS) - 1))
        char_index = max(0, min(char_index, len(self.ASCII_CHARS) - 1))
        
        return self.ASCII_CHARS[char_index]
    
    def to_ascii_text(self, grayscale_frame):
        """
        Convert grayscale image to ASCII text.
        
        Each pixel becomes an ASCII character based on brightness.
        Light pixels -> light characters (spaces, dots)
        Dark pixels -> dark characters (pound signs, at signs)
        
        Args:
            grayscale_frame: Numpy array (height, width) with values 0-255
            
        Returns:
            List of strings, one per row
        """
        height, width = grayscale_frame.shape
        ascii_lines = []
        
        for row in range(height):
            line = ""
            for col in range(width):
                pixel = grayscale_frame[row, col]
                char = self._pixel_to_char(pixel)
                line += char
            ascii_lines.append(line)
        
        return ascii_lines
    
    def get_character_density(self, char):
        """
        Get visual density of an ASCII character (0=light, 1=dark).
        
        Args:
            char: ASCII character
            
        Returns:
            Density value 0-1
        """
        return self._density_cache.get(char, 0.0)
