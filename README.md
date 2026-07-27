# ASCII Art

## What this app does
This Android app takes live camera input, downsamples it into a coarse grid, and renders that grid as either:
- a de-res image (`Image` mode), or
- ASCII art (`ASCII` mode).

It also supports a **Colour** toggle:
- **Off:** output is grayscale-based.
- **On:** each de-res cell is assigned a sampled color, and ASCII glyphs (and Image mode cells) use that color.

## Why this was created
This project was created as an exploration of **GitHub Copilot’s capability** to iteratively design, implement, debug, and refine a non-trivial real-time graphics pipeline in an Android app.

## High-level graphics pipeline
1. Acquire camera frames with CameraX `ImageAnalysis` using `KEEP_ONLY_LATEST`.
2. Read luma (Y) and downsample according to user scale factor.
3. Apply contrast adjustment to luma values before output mapping.
4. Build:
   - a grayscale de-res bitmap, and
   - (when Colour is enabled) a per-cell ARGB color grid sampled from YUV.
5. Rotate processed output to device orientation.
6. Render either image cells or ASCII cells in Compose.

## ASCII mapping algorithm
For each de-res cell:
1. Use the cell grayscale intensity (0..255).
2. Map that value to an index in a density-sorted printable ASCII character list.
3. Render the selected character at the corresponding on-screen cell bounds.

Character choice logic does **not** change when Colour is enabled; colour is applied as an additional rendering layer.

## Character density model
Character density is computed by rasterizing each candidate printable ASCII character into a fixed one-character bitmap grid and measuring occupancy:

`density = lit_pixels / total_pixels`

Characters are sorted by this density, from sparse (e.g. space) to dense. Grayscale intensity is then mapped across that ordered list.

## Current controls
- **Scale factor** slider: controls downsampling resolution.
- **Contrast** slider: adjusts contrast before mapping.
- **Mode chips**: `Image` / `ASCII`.
- **Colour** toggle: enables per-cell color output.

## Notes
- App is portrait-locked.
- Edge-to-edge/system bar transparency is configured.
- Camera permission is requested at runtime.

## Architecture Diagrams

### Grayscale Mode Sequence
```mermaid
sequenceDiagram
    participant Camera
    participant CFA as CameraFrameAnalyzer
    participant IP as ImageProcessor
    participant AA as AsciiArt
    participant UI as AsciiPreviewScreen

    Camera->>CFA: ImageProxy (YUV)
    CFA->>IP: processLumaFrame()
    IP->>IP: Read Y plane, downsample
    IP->>IP: Apply contrast adjustment
    IP->>IP: Create grayscale ARGB bitmap
    IP-->>CFA: FrameProcessingResult (bitmap, null)
    CFA->>CFA: Rotate bitmap to device orientation
    alt ASCII Mode
        CFA->>AA: toAsciiText(bitmap)
        AA-->>CFA: ASCII text
    else Image Mode
        CFA->>CFA: Use bitmap directly
    end
    CFA->>UI: onFrameProcessed(displayBitmap, asciiText, null)
    UI->>UI: Render to Canvas or Image
```

### Colour Mode Sequence
```mermaid
sequenceDiagram
    participant Camera
    participant CFA as CameraFrameAnalyzer
    participant IP as ImageProcessor
    participant AA as AsciiArt
    participant UI as AsciiPreviewScreen

    Camera->>CFA: ImageProxy (YUV)
    CFA->>IP: processLumaFrame(colorEnabled=true)
    IP->>IP: Read Y plane, downsample
    IP->>IP: Apply contrast adjustment
    IP->>IP: Sample U,V planes for per-cell color
    IP->>IP: Convert YUV to ARGB for each cell
    IP->>IP: Create grayscale ARGB bitmap
    IP-->>CFA: FrameProcessingResult (bitmap, colorGrid)
    CFA->>CFA: Rotate bitmap to device orientation
    CFA->>CFA: Rotate color grid to device orientation
    alt Image Mode + Colour
        CFA->>CFA: Create colored bitmap from grid
    else ASCII Mode + Colour
        CFA->>AA: toAsciiText(bitmap)
        AA-->>CFA: ASCII text
    end
    CFA->>UI: onFrameProcessed(displayBitmap, asciiText, colorGrid)
    UI->>UI: Render with colors from grid
```

### Static Class Diagram
```mermaid
classDiagram
    class MainActivity {
        onCreate()
        setContent()
    }
    
    class AsciiPreviewScreen {
        scaleFactor
        contrastFactor
        colorEnabled
        displayMode
        liveBitmap
        liveAsciiText
        liveAsciiColors
    }
    
    class CameraAnalysisPipeline {
        scaleFactor: Int
        contrastFactor: Float
        colorEnabled: Boolean
        displayMode: AsciiDisplayMode
    }
    
    class CameraFrameAnalyzer {
        scaleFactorProvider()
        contrastFactorProvider()
        colorEnabledProvider()
        displayModeProvider()
        onFrameProcessed()
        analyze(image: ImageProxy)
    }
    
    class ImageProcessor {
        +processLumaFrame()$
        -yuvToArgb()$
    }
    
    class FrameProcessingResult {
        grayscaleBitmap: Bitmap
        asciiColors: IntArray
    }
    
    class AsciiArt {
        +toAsciiText()$
    }
    
    class AsciiDisplayMode {
        <<enum>>
        IMAGE_ONLY
        ASCII_ONLY
    }
    
    MainActivity --> AsciiPreviewScreen
    AsciiPreviewScreen --> CameraAnalysisPipeline
    CameraAnalysisPipeline --> CameraFrameAnalyzer
    CameraFrameAnalyzer --> ImageProcessor
    ImageProcessor --> FrameProcessingResult
    CameraFrameAnalyzer --> AsciiArt
    AsciiPreviewScreen --> AsciiDisplayMode
```

