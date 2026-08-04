# Code review — 4 August 2026

Full read of the ~1,800 lines of Kotlin under `app/src/main`, plus the Gradle config,
manifest and tests.

Line references are pinned to the commit that removed the dead code listed below.
Numbering is preserved from the original review so items stay traceable; items 1 and 6,
and most of the dead-code list, have since been fixed.

| Status | Meaning |
|---|---|
| ✅ Fixed | Landed on `main`, commit noted |
| ⬜ Open | Still present |

---

## ✅ Fixed

### ASCII glyph density was inverted — `840b396`

Not in the original list; surfaced while writing tests for item 6.

`268b241` changed the intensity-to-charset mapping from `gray` to `255 - gray`,
described as "dense chars for bright areas". But `sortedChars` ascends in density —
index 0 is `' '`, the last index is the heaviest glyph — so the original mapping already
did that. The inversion flipped it, and because ASCII mode draws white glyphs on black
with no display-time inversion, bright parts of the scene rendered blank while dark
parts got inked.

The README documented the mechanism that *causes* the bug (`255 - intensity`) alongside
the outcome that mechanism prevents; corrected in the same commit.

### 1. Image mode rendered a photographic negative — `aa5e9ee`

The same commit inverted **both** branches of `ImagePreview` — a `-1x + 255` `ColorMatrix`
on the grayscale `Image`, and `invertArgb()` on every cell of the colour grid — so Image
mode was a negative with Colour both on and off.

ASCII mode legitimately needs density to track brightness, because ink on a black
background *is* the light. Image mode paints luminance directly, so inverting it only
ever produces a negative. Both inversions and the dead `invertArgb` helper are gone.

### 6. Charset density measured ~13x more often than needed — `b36e2e9`

`buildSortedCharset` measured ink coverage inside a `sortedBy {}` selector. `sortedBy`
delegates to `sortedWith(compareBy(selector))`, and `compareBy` invokes its selector on
*both* operands of *every* comparison — so the draw + `getPixels` round trip ran
~2n log n times instead of n. For the 95-character PRINTABLE set that is roughly 1,200
measurements instead of 95.

### Dead code in the render and frame-queue paths

Removed in the same commit as this document:

- `FrameQueueState.shouldProcessFrame`, `recordProcessedFrame` and `lastProcessedTimeMs` —
  no production caller. Their six unit tests went too, taking `FrameQueueStateTest` from
  16 tests to 10; the coverage it reported was for code the app never ran.
- `AsciiGridPreview.drawSourceImage` and its `drawImage` branch — `false` at both call
  sites, so the branch was unreachable. This also retired the `bitmap.asImageBitmap()`
  wrapper allocated on every draw purely to feed it, plus the `IntOffset`/`IntSize` imports.
- `onSurfaceColor` — orphaned by `268b241`; assigned every recomposition, never read.
- The composition-time `textPaint.color` assignment — both draw paths set the colour
  themselves before any `drawText`, so it never survived to a draw.

### 2. Video tab ignored the pipeline entirely in Image mode

`pollForFrames` only captured when `displayMode == ASCII`, and the Image branch handed the
surface to a `StyledPlayerView`. Image mode on the video tab was plain playback — no
de-res, no contrast, and the Scale and Contrast sliders did nothing — while the same mode
on the camera tab showed the processed grid.

Capture now runs whenever the player is playing, and the Image branch renders
`ImagePreview` with the same arguments the camera tab passes it. `StyledPlayerView` is
gone. Only the ASCII text conversion is mode-dependent, so `processQueuedFrames` skips
`toAsciiText` in Image mode.

Image mode costs more than it did — a GPU→CPU `getBitmap` plus a full `processBitmap` pass
per frame, where it previously rendered direct. That is inherent to applying the pipeline.

### 5. `captureTextureView` was not a key of the effect that consumed it

Fixed as a consequence of item 2. With both modes rendering from captured frames,
ExoPlayer's render target no longer changes with `displayMode`, so the render-target
effect rekeyed from `(displayMode, exoPlayer)` to `(exoPlayer, captureTextureView)`.

The `AndroidView` factory assigns `captureTextureView` after the first composition. Under
the old keys, if it arrived after that effect's last run, `setVideoTextureView` never
fired and the ASCII view sat on "Waiting for video frames..." until the user toggled the
mode. Keying on the TextureView makes the ordering irrelevant.

### 7. `rowOffsets` was scanned and allocated every frame for a constant

`toAsciiText` emits exactly `width` chars per row with `\n` between, so row `y` always
starts at `y * (bitmap.width + 1)`. The `remember(asciiText)` block did a full ~32K-char
scan plus an `IntArray` allocation per frame to rediscover that. Both draw loops now
compute row bounds directly and the block is gone.

That arithmetic depends on a layout invariant of `toAsciiText` which nothing pinned, so
`AsciiArtInstrumentedTest.toAsciiText_laysRowsOutAtAFixedStride` now asserts it — a change
to the separator fails the test instead of silently shifting every row on screen.

### 10. Per-pixel branch that was a plain copy

`colorPixels[i] = argb` was exactly `bitmapInputPixels[i]`, so it is now a single
`copyOf(size)` before the loop, dropping a branch from the hot path. `copyOf(size)` also
trims the reusable input buffer, which can be longer than the current frame needs; the
previous `IntArray(size)` was already exactly sized, so the result is identical.

### 12. 60Hz main-thread poll that never stopped

The `while(true) { … delay(16) }` loop ran for the whole lifetime of the Video tab, even
with no video loaded and the player paused.

A `Player.Listener` now feeds a `MutableStateFlow<Boolean>`, and the loop opens each
iteration with `isPlaying.first { it }`. While playing that is a StateFlow read returning
immediately; when playback stops the loop suspends until the listener wakes it. A
loaded-but-paused video, or the tab merely being open, now costs nothing.

---

## ⬜ Open — defects

### 3. Bitmap recycled while still held in Compose state

`ExoPlayerFrameCapture.kt:198-199`

`lastDisplayedBitmap?.recycle()` destroys the previous frame's bitmap, which is still the
value of `videoBitmap` state and may not have been drawn yet. It doesn't crash today only
because `AsciiGridPreview` never draws the bitmap — it reads just `width` and `height`,
which stay legal on a recycled bitmap. Anything that starts actually drawing it turns this
into an immediate `Canvas: trying to use a recycled bitmap`. (Before the dead-code sweep
the same reprieve came from `drawSourceImage` being `false` at every call site.)

### 4. Shared scratch buffer can escape to the UI

`CameraFrameAnalyzer.kt:142`

`ImageProcessor.kt:17-18` documents the invariant that `rotateColorGridIfNeeded` *always*
copies before the buffer is reused. The `else ->` branch returns `colors` — the live
`lumaColorPixels` singleton — straight to Compose state, where the next frame overwrites
it mid-draw. Only reachable if `rotationDegrees` isn't a multiple of 90 (CameraX doesn't
currently produce that), but it silently breaks the stated contract.

---

## ⬜ Open — inefficiencies

### 8. Up to 32K `drawRect` calls per frame

`AsciiPreviewScreen.kt:448-456`

Colour Image mode draws one rect per cell. `Bitmap.createBitmap(asciiColors, w, h, ARGB_8888)`
drawn once with `FilterQuality.None` produces an identical result in a single draw op.

### 9. `setPixels` → `getPixels` round trip

`ImageProcessor.kt:133` → `AsciiArt.kt:51-52`

On the video path, `processBitmap` writes `bitmapOutputPixels` into a fresh bitmap, then
`toAsciiText` immediately reads that same data back out into a newly allocated
`IntArray(width*height)`. Passing the int array (or a grayscale `ByteArray`) directly to
the ASCII mapper skips two copies and a per-frame allocation.

### 11. Two full-bitmap rotations per camera frame

`CameraFrameAnalyzer.kt:74-93, 95-145`

Every frame allocates a rotated `Bitmap` (discarding the just-built one, never recycled —
the comment at `:90` explicitly defers to GC) plus a rotated `IntArray`. Both disappear if
`processLumaFrame` writes into rotated output indices during the downsample it is already
doing.

---

## ⬜ Open — dead code and simplification

- **`AsciiCharsetPreset.EXTENDED`** (`AsciiArt.kt:18, 77-86`) — no caller ever passes it. Left in
  place during the dead-code sweep because removing it leaves a single-valued enum and raises
  whether the `preset` parameter should go entirely; that is a design call, not a no-brainer.
- **`VideoFileTabContent`** (`AsciiPreviewScreen.kt:266`) — a pure pass-through to
  `ExoPlayerVideoFileTab`. Simplification rather than dead code, since it is executed.
- **`VideoFilePlayer.kt:59, 68-75, 104-131`** — `exoPlayer` as `mutableStateOf` set from inside
  `DisposableEffect` forces an extra recomposition round-trip that every downstream effect then
  has to null-guard; `remember { ExoPlayer.Builder(context).build() }` removes the nullability
  and the guards. The three `rememberUpdatedState` setter wrappers are also unnecessary — a
  lambda capturing a `MutableState` delegate stays valid across recomposition.
- **Duplicated letterbox math** — `AsciiPreviewScreen.kt:428-444` and `:531-547` are the same
  aspect-fit computation.
- **`AsciiPreviewScreen.kt:505-515`** — `CACHE_CELL_WIDTH` etc. and `TEXT_SIZE_CELL_FRACTION` are
  `SCREAMING_CASE` locals inside a composable; they belong at file scope as `private const val`.
- **`AsciiPreviewScreen.kt:113`** — `.coerceIn(2, 48)` is redundant against `valueRange = 2f..48f`.
  The slider also has no `steps`, so dragging fires many `onValueChange` calls that resolve to the
  same `Int`.
- **Hardcoded UI strings** — "Live Camera", "Video File", "Load", "No video loaded…", "Waiting for
  video frames…", and the Play/Pause/Restart `contentDescription`s bypass `strings.xml`, which the
  rest of the screen uses consistently.
- **`READ_EXTERNAL_STORAGE` in the manifest** — the app uses SAF (`OpenDocument` +
  `takePersistableUriPermission`), which grants per-URI access. The permission is never requested
  at runtime and grants nothing on API 24+.
- **ExoPlayer 2.19.1** is end-of-life; every usage warns as deprecated (`ExoPlayer`, `MediaItem`,
  `Player`, `StyledPlayerView`). `androidx.media3` is the maintained successor.

---

## ⬜ Open — test coverage

`AsciiArt` gained instrumented coverage in PR #20 (`AsciiArtInstrumentedTest`, 7 tests):
charset ordering and the intensity→glyph mapping. `FrameQueueStateTest` is down to 10 tests
after the dead-code sweep, and now covers only code the app actually runs. Still uncovered:

- **`ImagePreview`** — no tests at all. The item 1 fix was confirmed by eye on device. A Compose
  `captureToImage()` test asserting a white input pixel renders white would close the gap, and
  would have caught the original `268b241` regression.
- **`ImageProcessor`** — untested.
- **`CameraFrameAnalyzer.rotateColorGridIfNeeded`** — pure logic, trivially unit-testable, and the
  place an off-by-one would be hardest to spot by eye.
- **`ExampleUnitTest` / `ExampleInstrumentedTest`** — untouched template boilerplate.

---

## Build health

`./gradlew :app:compileDebugKotlin` succeeds. All remaining warnings are deprecations:
ExoPlayer 2.x throughout, `TabRow` (replaced by `PrimaryTabRow`/`SecondaryTabRow`), and the
edge-to-edge window APIs in `MainActivity` (`statusBarColor`, `navigationBarColor`,
`isStatusBarContrastEnforced`).
