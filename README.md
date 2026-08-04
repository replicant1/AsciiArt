# ASCII Art

## What this app does
This Android app provides two input sources for real-time ASCII art generation:

### Live Camera Tab
Takes live camera input, downsamples it into a coarse grid, and renders that grid as either:
- a de-res image (`Image` mode), or
- ASCII art (`ASCII` mode).

### Video File Tab
Loads video files from device storage and applies the same pipeline in real-time, in both `Image` and `ASCII` mode.

Both tabs support a **Colour** toggle:
- **Off:** output is grayscale-based.
- **On:** each de-res cell is assigned a sampled color, and ASCII glyphs (and Image mode cells) use that color.

## Screenshots

| Live Camera Tab | Video File Tab |
|---|---|
| ![Live Camera tab showing ASCII art output from the device camera][screenshot-live] | ![Video File tab showing the Load, Restart, Play and Pause control bar with ASCII art output from a video file][screenshot-video] |

[screenshot-live]: docs/screenshot_live_camera.png
[screenshot-video]: docs/screenshot_video_file.png

## Why this was created
This project was created as an exploration of **GitHub Copilot’s capability** to iteratively design, implement, debug, and refine a non-trivial real-time graphics pipeline in an Android app.

## High-level graphics pipeline

### Live Camera Pipeline
1. Acquire camera frames with CameraX `ImageAnalysis` using `KEEP_ONLY_LATEST`.
2. Read luma (Y) and downsample according to user scale factor.
3. Apply contrast adjustment to luma values before output mapping.
4. Write each cell straight to its rotated destination index, so the grid already matches device
   orientation when the loop ends — there is no separate rotation pass.
5. Build:
   - a grayscale de-res bitmap, and
   - (when Colour is enabled) a per-cell ARGB color grid sampled from YUV.
6. Render either image cells or ASCII cells in Compose.

### Video File Pipeline
1. Load video file using ExoPlayer 2.19.1.
2. While playback is running, poll the playback position at ~60Hz to detect new frames. The loop
   suspends when playback stops and is woken by a `Player.Listener`, so an idle or paused tab costs
   nothing.
3. Extract frames via `TextureView.getBitmap()` from a hidden zero-alpha `TextureView` that ExoPlayer
   renders to. Bitmaps are reused from a pool (`ConcurrentLinkedQueue`) to eliminate per-frame
   allocation.
4. Process frames through the same pipeline as live camera:
   - Downsample according to scale factor (applied at capture, via the requested bitmap size)
   - Apply contrast adjustment
   - Generate grayscale bitmap and optional color grid
5. Render the processed grid in both `Image` and `ASCII` mode, with real-time parameter
   responsiveness. Only the ASCII text conversion is mode-dependent.

## ASCII mapping algorithm
For each de-res cell:
1. Use the cell grayscale intensity (0..255).
2. Map the intensity directly onto an index in the density-sorted printable ASCII character list, which runs from sparsest to densest: `index = intensity * (charCount - 1) / 255`.
3. Because the list ascends in density, bright areas of the scene reach the dense end (e.g. `@`, `#`) and dark areas stay near the sparse end (e.g. `.`, space) — which is correct for the white-on-black display, where ink means light. Do **not** invert the intensity to `255 - intensity`: that blanks out the bright parts of the scene and inks the dark ones.
4. Render the selected character at the corresponding on-screen cell bounds.

Character choice logic does **not** change when Colour is enabled; colour is applied as an additional rendering layer.

Because intensity is a single byte, the whole mapping is only 256 distinct answers. It is
precomputed once into a `CharArray(256)` and the per-pixel work is a single array read.

## Character density model
Character density is computed by rasterizing each candidate printable ASCII character into a fixed one-character bitmap grid and measuring occupancy:

`density = lit_pixels / total_pixels`

Characters are sorted by this density, from sparse (e.g. space) to dense. Grayscale intensity is then mapped across that ordered list.

## Current controls
- **Scale factor** slider: controls downsampling resolution (2–48×).
- **Contrast** slider: adjusts contrast before mapping.
- **Mode chips**: `Image` / `ASCII` (radio group, shared across both tabs).
- **Colour** toggle: enables per-cell color output (affects both Live Camera and Video File).
- **Tab selector**: switch between Live Camera and Video File input sources.

## Notes
- App is portrait-locked.
- Edge-to-edge/system bar transparency is configured.
- Camera permission is requested at runtime.
- Video files are chosen through the system file picker, which opens in `/sdcard/Download/` — any location the picker can reach will work.
- Scale factor and contrast adjustments update in real-time on both tabs.
- Colour toggle applies dynamically (no need to restart video playback).

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
    CFA->>IP: processLumaFrame(displayMode, rotationDegrees)
    IP->>IP: Read Y plane, downsample
    IP->>IP: Apply contrast adjustment
    IP->>IP: Write each cell to its rotated index
    alt ASCII Mode
        IP->>AA: toAsciiText(lumaPixels, gridWidth, gridHeight)
        AA-->>IP: ASCII text
    else Image Mode
        IP->>IP: Create grayscale ARGB display bitmap
    end
    IP-->>CFA: FrameProcessingResult (displayBitmap, asciiText, null, grid size)
    CFA->>UI: onFrameProcessed(frame)
    UI->>UI: Render via ImagePreview or AsciiGridPreview
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
    CFA->>IP: processLumaFrame(colorEnabled=true, displayMode, rotationDegrees)
    IP->>IP: Read Y plane, downsample
    IP->>IP: Apply contrast adjustment
    IP->>IP: Sample U,V planes, convert YUV to ARGB per cell
    IP->>IP: Write colour to its rotated index (luma too, in ASCII mode)
    alt ASCII Mode
        IP->>AA: toAsciiText(lumaPixels, gridWidth, gridHeight)
        AA-->>IP: ASCII text
    else Image Mode
        IP->>IP: Create display bitmap from the colour grid
    end
    IP-->>CFA: FrameProcessingResult (displayBitmap, asciiText, colorGrid, grid size)
    CFA->>UI: onFrameProcessed(frame)
    alt Image Mode
        UI->>UI: ImagePreview draws the colour bitmap
    else ASCII Mode
        UI->>UI: AsciiGridPreview tints each glyph from colorGrid
    end
```

### Video File Processing Sequence
```mermaid
sequenceDiagram
    participant UI as ExoPlayerVideoFileTab
    participant EFL as ExoPlayerFrameListener
    participant TV as TextureView (hidden)
    participant IP as ImageProcessor
    participant AA as AsciiArt

    UI->>EFL: Create listener with provider lambdas
    UI->>EFL: startListening()
    loop While playback is running
        EFL->>EFL: Poll currentPosition every ~16ms
        EFL->>TV: getBitmap(pooledBitmap) at size / scaleFactor
        TV-->>EFL: Bitmap (reused from pool)
        EFL->>IP: processBitmap(bitmap, contrastFactor, colorEnabled, displayMode)
        alt ASCII Mode
            IP->>AA: toAsciiText(grayscalePixels, width, height)
            AA-->>IP: ASCII text
        else Image Mode
            IP->>IP: Create display bitmap (colour grid or grayscale)
        end
        IP-->>EFL: FrameProcessingResult (displayBitmap, asciiText, colors, grid size)
        EFL->>UI: onFrameProcessed(frame)
        UI->>UI: Render via ImagePreview or AsciiGridPreview
    end
    Note over EFL: When playback stops the loop suspends<br/>until Player.Listener signals isPlaying
    UI->>EFL: refreshCurrentFrame() on a display mode change<br/>so a paused video re-renders in the new mode
```

### Shared Parameter Update Flow
```mermaid
sequenceDiagram
    participant APS as AsciiPreviewScreen
    participant VFT as ExoPlayerVideoFileTab
    participant RUS as rememberUpdatedState
    participant EFL as ExoPlayerFrameListener

    APS->>VFT: scaleFactor changes (recompose)
    VFT->>RUS: currentScaleFactor.value = scaleFactor
    Note over RUS: Value holder updated in place
    EFL->>RUS: scaleFactorProvider() reads current value
    EFL->>EFL: Next captured frame uses the new scale factor
    EFL->>VFT: onFrameProcessed(...)
    VFT->>VFT: Render updated output
```

### Static Class Diagram
```mermaid
classDiagram
    class MainActivity {
        onCreate()
    }

    class AsciiPreviewScreen {
        scaleFactor: Int
        contrastFactor: Float
        colorEnabled: Boolean
        displayMode: AsciiDisplayMode
        selectedTab: Int
    }

    class CameraAnalysisPipeline {
        onFrameProcessed()
    }

    class CameraFrameAnalyzer {
        scaleFactorProvider()
        contrastFactorProvider()
        colorEnabledProvider()
        displayModeProvider()
        onFrameProcessed()
        analyze(image: ImageProxy)
    }

    class ExoPlayerVideoFileTab {
        scaleFactor: Int
        contrastFactor: Float
        colorEnabled: Boolean
        displayMode: AsciiDisplayMode
    }

    class ExoPlayerFrameListener {
        startListening()
        stopListening()
        release()
        refreshCurrentFrame()
        -pollForFrames()
        -captureFrameToQueue()
        -processQueuedFrames()
    }

    class FrameQueueState {
        detectPlaybackRestart(currentTimeMs)
        shouldQueueFrame(currentTimeMs, skipRate)
        recordQueuedFrame(frameTimeMs)
    }

    class ImageProcessor {
        +processLumaFrame()$
        +processBitmap()$
        -yuvToArgb()$
    }

    class RotationMap {
        base: Int
        stepX: Int
        stepY: Int
        destWidth: Int
        destHeight: Int
    }

    class FrameProcessingResult {
        displayBitmap: Bitmap?
        asciiText: String
        asciiColors: IntArray?
        gridWidth: Int
        gridHeight: Int
    }

    class AsciiArt {
        +toAsciiText(grayscalePixels, width, height)$
        -glyphForIntensity: CharArray
        -buildSortedCharset()$
    }

    class AsciiDisplayMode {
        <<enum>>
        IMAGE
        ASCII
    }

    MainActivity --> AsciiPreviewScreen
    AsciiPreviewScreen --> CameraAnalysisPipeline
    AsciiPreviewScreen --> ExoPlayerVideoFileTab
    AsciiPreviewScreen --> AsciiDisplayMode
    CameraAnalysisPipeline --> CameraFrameAnalyzer
    CameraFrameAnalyzer --> ImageProcessor
    ExoPlayerVideoFileTab --> ExoPlayerFrameListener
    ExoPlayerFrameListener --> FrameQueueState
    ExoPlayerFrameListener --> ImageProcessor
    ImageProcessor --> RotationMap
    ImageProcessor --> FrameProcessingResult
    ImageProcessor --> AsciiArt
```

| Class | Description |
|---|---|
| `MainActivity` | The app's single Android `Activity`. Configures edge-to-edge / transparent system bar styling and hosts the root Compose content via `setContent`. |
| `AsciiPreviewScreen` | Root composable screen. Owns all shared UI state — scale factor, contrast, colour toggle, display mode and selected tab — and renders the control panel plus the tab selector. |
| `CameraAnalysisPipeline` | Private composable that wires up CameraX `ImageAnalysis`, binds it to the `LifecycleOwner`, and forwards each raw camera frame to a `CameraFrameAnalyzer` instance. |
| `CameraFrameAnalyzer` | Implements `ImageAnalysis.Analyzer`. Receives raw YUV `ImageProxy` frames from CameraX, passes the sensor rotation and the current display mode through to `ImageProcessor` (which applies the rotation while downsampling), and posts the finished `FrameProcessingResult` to the UI thread. |
| `ImageProcessor` | Singleton. Downsamples luma data from a YUV `ImageProxy` or an existing `Bitmap`, applies contrast adjustment, and produces exactly what the current display mode draws — see `FrameProcessingResult`. Applies rotation inside the downsample loop via `RotationMap`. Keeps pre-allocated `IntArray` buffers for the data it consumes internally; arrays that escape to Compose state are allocated per frame. |
| `RotationMap` | A right-angle rotation expressed as an affine index map — `dstIndex = base + stepX * x + stepY * y` — so the downsample loop can write each cell directly to its rotated position. Pure arithmetic with no Android dependency, which is what makes it unit-testable. |
| `FrameProcessingResult` | Immutable data class that carries the output of a single `ImageProcessor` call: the de-res grid dimensions, the ASCII text for ASCII mode, an optional `IntArray` of per-cell ARGB colours, and the `Bitmap` Image mode draws — null in ASCII mode, which draws no image. Building only what the mode shows is what keeps a full-size bitmap from being allocated and filled on every frame regardless of whether anything reads it. |
| `AsciiArt` | Singleton. Converts a grid of grayscale pixels to a multi-line ASCII `String` by mapping each pixel's intensity to a character from a density-sorted printable ASCII set — brighter cells map to denser glyphs. The mapping is precomputed into a `CharArray(256)` on first use, so the per-pixel cost is one array read. |
| `AsciiDisplayMode` | Enum with two values — `IMAGE` (render de-res bitmap cells) and `ASCII` (render character glyphs) — shared across both tabs. |
| `ExoPlayerVideoFileTab` | Composable for the Video File tab. Creates and manages the `ExoPlayer` instance and its lifecycle. Provides a persistent control bar (Load, Restart, Play, Pause) visible in both modes. Delegates frame capture to `ExoPlayerFrameListener` and renders the output through `ImagePreview` or `AsciiGridPreview`. |
| `ExoPlayerFrameListener` | Bridges ExoPlayer playback and the de-res pipeline. Polls the player position at ~60 Hz on the main thread while playback runs, captures rendered frames via `TextureView.getBitmap()` into a `Channel<Bitmap>`, and processes them on an IO thread via `ImageProcessor`. Suspends its poll loop when playback stops, and re-captures the displayed frame on demand so a paused video still follows a display mode change. |
| `FrameQueueState` | Frame-timing state for the video pipeline, kept separate from the coroutine plumbing so it can be unit-tested: enforces a minimum gap between captures, applies the frame-skip rate, and detects backward seeks or loops. |
