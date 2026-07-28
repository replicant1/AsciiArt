# ASCII Art Live Camera for Raspberry Pi Zero 2

Python implementation of the Live Camera pipeline from the Android ASCII Art app, adapted for Raspberry Pi Zero 2 with Camera Module 2.

## Features

- **Live camera capture** from Pi Camera Module 2
- **Real-time image processing** pipeline:
  - YUV420 → RGB → Grayscale conversion
  - Automatic camera rotation (90° for typical Pi Camera mounting)
  - Downscaling for performance (configurable)
  - Contrast adjustment
- **ASCII art rendering** based on pixel brightness
- **ncurses terminal display** on HDMI screen
- **Responsive controls** - Press 'q' to quit

## Architecture

Similar to the Android app, uses a producer-consumer pattern:

```
Camera Thread              Main Thread           Display Thread
    |                          |                      |
    v                          |                      |
Capture Frame --queue--> Process --queue--> Render to Terminal
    |                    (YUV→Gray)             (ncurses)
    |                      |
    |              Scale + Contrast
    |                      |
    v                      v
  (continuous)        ASCII Art
  (30 FPS)           Generation
                           |
                           v
                      (continuous)
```

## Hardware Requirements

- Raspberry Pi Zero 2 W or better
- Camera Module 2 (CSI ribbon connection)
- HDMI display
- Power supply (5V 2.5A recommended)

## Software Setup

### 1. Install Dependencies

```bash
sudo apt-get update
sudo apt-get install -y python3-pip python3-libcamera python3-picamera2
pip3 install -r requirements.txt
```

### 2. Enable Camera

```bash
sudo raspi-config
# Navigate to: Interface Options > Camera > Enable
```

### 3. Run Application

```bash
cd /pi
python3 ascii_camera.py
```

**Optional arguments:**
```bash
python3 ascii_camera.py --scale 8 --contrast 1.0 --fps 30 --rotation 90 --verbose
```

- `--scale`: Downscale factor (smaller = faster, less detailed)
- `--contrast`: Brightness adjustment (1.0 = normal)
- `--fps`: Target frame rate
- `--rotation`: Camera rotation (0, 90, 180, 270 degrees)
- `--verbose`: Detailed logging

## Performance Notes

Raspberry Pi Zero 2 is resource-constrained:

- **Frame extraction**: ~50-100ms per frame (MediaMetadataRetriever on Android is faster)
- **Image processing**: ~20-30ms (CPU-bound)
- **ASCII generation**: ~10-15ms (depends on resolution)
- **Terminal rendering**: ~5-10ms

**Typical throughput**: 8-12 FPS (vs 30 FPS on Android due to CPU limitation)

### Optimization Tips

1. **Reduce scale factor** - Use `--scale 12` instead of 8 for faster processing
2. **Lower target FPS** - Use `--fps 15` to reduce CPU load
3. **Disable contrast** - Set `--contrast 1.0` (default) to skip processing
4. **Use simpler characters** - Modify `ASCII_CHARS` in `ascii_art.py`

## File Structure

```
pi/
├── ascii_camera.py           # Main application entry point
├── requirements.txt          # Python dependencies
├── README.md                 # This file
└── src/
    ├── camera.py             # Camera capture (picamera2)
    ├── image_processor.py     # YUV→RGB→Grayscale, scaling, contrast
    ├── ascii_art.py          # Brightness→ASCII character mapping
    └── display.py            # ncurses terminal rendering
```

## Comparison to Android Implementation

| Aspect | Android | Raspberry Pi |
|--------|---------|--------------|
| **Language** | Kotlin | Python |
| **Camera API** | CameraX | picamera2 |
| **Threading** | Coroutines | Python Threads |
| **Display** | Jetpack Compose | ncurses |
| **Frame Rate** | 30 FPS (typical) | 10-12 FPS |
| **Input** | Touch + Live Camera/Video File | Live Camera only |

## Troubleshooting

### Camera not found
```bash
vcgencmd get_camera
# Should show: supported=1 detected=1
```

### Permission denied
```bash
sudo usermod -a -G video $USER
# Log out and back in
```

### Low frame rate
- Reduce scale factor: `--scale 12`
- Reduce FPS target: `--fps 15`
- Check CPU load: `top`

### Display issues
- Ensure terminal is at least 80x24 characters
- Try different terminal applications
- Check HDMI connection

## Future Enhancements

- [ ] Color support (using terminal ANSI colors)
- [ ] Multiple display backends (OLED, LCD)
- [ ] Parameter adjustment UI (scale, contrast live)
- [ ] Performance metrics overlay
- [ ] Video file playback (like Android version)
- [ ] Web interface for remote viewing

## License

Same as parent AsciiArt project
