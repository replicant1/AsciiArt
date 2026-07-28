"""
Ncurses-based display module for ASCII art rendering.
Handles terminal rendering with color support.
"""

import curses
import logging
from threading import Lock

logger = logging.getLogger(__name__)


class NcursesDisplay:
    """Renders ASCII art to ncurses terminal."""
    
    def __init__(self, enable_color=False):
        """
        Initialize ncurses display.
        
        Args:
            enable_color: Whether to enable color rendering (requires color terminal)
        """
        self.enable_color = enable_color
        self.stdscr = None
        self.lock = Lock()
        self.initialized = False
        self.max_height = 0
        self.max_width = 0
    
    def start(self):
        """Initialize ncurses and set up terminal."""
        try:
            self.stdscr = curses.initscr()
            
            # Get terminal dimensions
            self.max_height, self.max_width = self.stdscr.getmaxyx()
            logger.info(f"Terminal size: {self.max_width}x{self.max_height}")
            
            # Configure ncurses
            curses.noecho()  # Don't echo user input
            curses.cbreak()  # Don't wait for Enter
            self.stdscr.nodelay(True)  # Non-blocking input
            self.stdscr.timeout(0)
            
            # Hide cursor
            try:
                curses.curs_set(0)
            except:
                pass  # Some terminals don't support hiding cursor
            
            # Set up colors if enabled
            if self.enable_color and curses.has_colors():
                curses.start_color()
                curses.use_default_colors()
                # Define color pairs for ASCII art shading
                curses.init_pair(1, curses.COLOR_BLACK, -1)      # Dark
                curses.init_pair(2, curses.COLOR_WHITE, -1)      # Light
            
            self.initialized = True
            logger.info("Ncurses display initialized")
            
        except Exception as e:
            logger.error(f"Failed to initialize ncurses: {e}")
            self.cleanup()
            raise
    
    def render(self, ascii_lines):
        """
        Render ASCII art to terminal.
        
        Args:
            ascii_lines: List of strings (one per row)
        """
        if not self.initialized or not self.stdscr:
            return
        
        with self.lock:
            try:
                self.stdscr.clear()
                
                # Render each line, clipping to terminal width
                for row, line in enumerate(ascii_lines):
                    if row >= self.max_height - 1:  # Leave room for info line
                        break
                    
                    # Truncate line to terminal width
                    truncated = line[:self.max_width]
                    
                    try:
                        self.stdscr.addstr(row, 0, truncated)
                    except curses.error:
                        # Can happen if writing at edge of terminal
                        pass
                
                # Draw info line at bottom
                info = f"ASCII Art - Camera Live Preview | q to quit"
                if len(info) > self.max_width:
                    info = info[:self.max_width - 1]
                
                try:
                    self.stdscr.addstr(
                        self.max_height - 1, 0, 
                        info, 
                        curses.A_REVERSE
                    )
                except curses.error:
                    pass
                
                self.stdscr.refresh()
                
            except Exception as e:
                logger.error(f"Error rendering: {e}")
    
    def get_key(self):
        """
        Get user input without blocking.
        
        Returns:
            Character code if key pressed, None otherwise
        """
        if not self.initialized or not self.stdscr:
            return None
        
        try:
            ch = self.stdscr.getch()
            if ch == curses.ERR:
                return None
            return chr(ch) if ch < 256 else None
        except:
            return None
    
    def cleanup(self):
        """Clean up ncurses and restore terminal."""
        if self.stdscr:
            try:
                # Show cursor
                curses.curs_set(1)
            except:
                pass
            
            try:
                curses.echo()
                curses.nocbreak()
                curses.endwin()
            except:
                pass
            
            self.stdscr = None
            self.initialized = False
            logger.info("Ncurses display cleaned up")
