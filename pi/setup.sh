#!/bin/bash
# Setup script for ASCII Art Live Camera on Raspberry Pi Zero 2
# Installs all system and Python dependencies

set -e

echo "================================"
echo "ASCII Art Camera - Setup Script"
echo "================================"
echo ""

# Check if running on Raspberry Pi
if ! grep -q "Raspberry Pi" /proc/device-tree/model 2>/dev/null; then
    echo "Warning: This doesn't appear to be a Raspberry Pi"
    read -p "Continue anyway? (y/n) " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        exit 1
    fi
fi

# Update package lists
echo "Updating package lists..."
sudo apt-get update

# Install system dependencies
echo "Installing system dependencies..."
sudo apt-get install -y libncurses5-dev python3-pip python3-libcamera python3-picamera2

# Install Python dependencies
echo "Installing Python dependencies..."
pip3 install -r requirements.txt

# Check camera
echo ""
echo "Checking camera status..."
if vcgencmd get_camera 2>/dev/null | grep -q "supported=1"; then
    echo "✓ Camera detected"
    if vcgencmd get_camera 2>/dev/null | grep -q "detected=1"; then
        echo "✓ Camera is connected"
    else
        echo "✗ Camera not detected - check CSI ribbon connection"
        echo "  Run: sudo raspi-config to enable camera"
    fi
else
    echo "✗ Camera not supported on this Pi"
fi

echo ""
echo "================================"
echo "Setup Complete!"
echo "================================"
echo ""
echo "To start the application:"
echo "  cd /path/to/pi"
echo "  python3 ascii_camera.py"
echo ""
echo "Optional arguments:"
echo "  --scale 8       Scale factor (smaller = faster)"
echo "  --contrast 1.0  Contrast adjustment"
echo "  --fps 30        Target frame rate"
echo "  --rotation 90   Camera rotation (0/90/180/270)"
echo "  --verbose       Enable debug logging"
echo ""
