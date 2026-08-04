# Code review — 4 August 2026

Full read of the ~1,800 lines of Kotlin under `app/src/main`, plus the Gradle config,
manifest and tests.

**10 of the 12 numbered items are fixed.** Defect 3 and inefficiency 9 remain, along with
4 entries on the simplification list. One bug not in the original review — the inverted
ASCII glyph density — was found while writing tests for item 6 and is recorded below.

Numbering is preserved from the original review so items stay traceable across the fixes,
which is why the fixed list below is not in numeric order.

| Status | Meaning |
| --- | --- |
| ✅ Fixed | Fix is in the tree. A commit is cited where the item maps to a single commit; the later fixes were grouped, so most are not. |
| ⬜ Open | Still present |

Line references describe the current state of the tree, not the state at review time — the
fixes have shifted them repeatedly. Every `File.kt:NNN` below is checked mechanically
against the file it names, so a reference that has drifted is a bug in this document.

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

### Dead code in the render and frame-queue paths — `5bae97a`

Code that was never executed or never read:

- `FrameQueueState.shouldProcessFrame`, `recordProcessedFrame` and `lastProcessedTimeMs` —
  no production caller. Their six unit tests went too, taking `FrameQueueStateTest` from
  16 tests to 10; the coverage it reported was for code the app never ran.
- `AsciiGridPreview.drawSourceImage` and its `drawImage` branch — `false` at both call
  sites, so the branch was unreachable. This also retired the `bitmap.asImageBitmap()`
  wrapper allocated on every draw purely to feed it, plus the `IntOffset`/`IntSize` imports.
- `onSurfaceColor` — orphaned by `268b241`; assigned every recomposition, never read.
- The composition-time `textPaint.color` assignment — both draw paths set the colour
  themselves before any `drawText`, so it never survived to a draw.

### `AsciiCharsetPreset` removed

`EXTENDED` was never passed by any caller. It was left alone during the dead-code sweep
because deleting just that constant leaves a single-valued enum and a parameter with one
legal argument — worse than either extreme — so the whole abstraction went instead:
the enum, `buildCharacterSet`'s `when`, and `toAsciiText`'s `preset` parameter.

`buildSortedCharset` now builds printable ASCII directly, and the charset cache is a
`by lazy` property rather than a `mutableMapOf` keyed by preset. That also retires a latent
threading bug: `getOrPut` on a plain `LinkedHashMap` is not thread-safe, and both the camera
analysis executor and the video IO dispatcher reach it. `by lazy` defaults to SYNCHRONIZED.

Recoverable from history if an extended charset is ever wanted.

### `VideoFileTabContent` pass-through removed

The composable took five parameters and forwarded all five to
`ExoPlayerVideoFileTab` unchanged. The tab now calls `ExoPlayerVideoFileTab` directly.

### Redundant slider clamps removed

`onValueChange = { scaleFactor = it.roundToInt().coerceIn(2, 48) }` clamped to the bounds
`valueRange = 2f..48f` already guarantees. The contrast slider had the identical redundancy
against `0.2f..2.0f` — not named in the original review, but fixed alongside.

`ImageProcessor` keeps its own `coerceIn` on both values. That one is not redundant: it
guards a public entry point rather than restating a constraint the caller already enforces.

**Correction to the original finding.** It also claimed the missing `steps` meant dragging
"fires many `onValueChange` calls that resolve to the same `Int`", implying wasted
recompositions. The lambda does run per drag event, but no recomposition follows:
`SnapshotMutableIntStateImpl` compares before writing and skips the notification when the
value is unchanged. Adding `steps` would also make the slider snap and draw 45 tick marks,
so it was not worth doing for a cost that does not exist.

### Composable-local constants hoisted to file scope

`AsciiGridPreview` declared `CACHE_CELL_WIDTH`, `CACHE_CELL_HEIGHT`, `CACHE_CHAR_WIDTH`,
`CACHE_BASELINE_OFFSET` and `TEXT_SIZE_CELL_FRACTION` as `SCREAMING_CASE` `val`s inside the
composable body — constants re-declared on every recomposition and flagged by the IDE for
violating Kotlin's local-variable naming convention.

They are now `private const val` at file scope, alongside the comment explaining the
`textMetricsCache` slot layout and the empirically chosen 0.92 text-height fraction. The
cache array's size comes from a `TEXT_METRICS_CACHE_SLOTS` constant rather than a bare `4`,
so the slot indices and the allocation cannot drift apart.

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

### 8. Up to 32K `drawRect` calls per frame

Colour Image mode drew one rect per grid cell — 32,400 canvas ops per frame at scaleFactor
8 on a Pixel 3, roughly 972,000/sec at 30fps — to paint what is really a small image scaled
up. The grayscale branch alongside it already handed a single bitmap to `Image`.

Colour mode now builds `Bitmap.createBitmap(asciiColors, width, height, ARGB_8888)` and
takes that same path, so `ImagePreview` collapses to one `Image` call. `ContentScale.Fit`
performs the aspect-fit-and-centre the loop did by hand, and `FilterQuality.None` is what
keeps cells blocky rather than interpolated.

This trades ~130 KB of short-lived allocation per frame for 32K canvas ops — a deliberate
swap, and consistent with `ImageProcessor`, which already builds a bitmap per frame on both
the camera and video paths. A `remember`ed mutable bitmap would avoid the allocation but
means mutating a bitmap Compose may still hold in a recorded display list — the hazard
behind item 3.

It also removes one of the two copies of the letterbox math noted in the simplification
list; `AsciiGridPreview` still has its own, because it needs the rect to place glyphs.

### 10. Per-pixel branch that was a plain copy

`colorPixels[i] = argb` was exactly `bitmapInputPixels[i]`, so it is now a single
`copyOf(size)` before the loop, dropping a branch from the hot path. `copyOf(size)` also
trims the reusable input buffer, which can be longer than the current frame needs; the
previous `IntArray(size)` was already exactly sized, so the result is identical.

### 11. Two full-bitmap rotations per camera frame

Every frame built the grid upright, then rotated it twice as a second pass: a
`Bitmap.createBitmap(src, matrix, true)` that discarded the just-built bitmap unrecycled,
plus a full copy loop for the colour grid.

Rotation is now folded into the downsample. Since every pixel is already written
individually, `rotationMap()` only changes *where* each one lands — a right-angle rotation
is affine in the source coordinates, `dstIndex = base + (stepX * x) + (stepY * y)`, so the
whole thing reduces to three integers and both passes disappear.

Measured on a Pixel 3 at the real grid size (135x240), median of 300 runs after warmup:

| Path | Before | After | Saved |
| --- | --- | --- | --- |
| Grayscale | 0.767 ms | 0.410 ms | 0.358 ms (47%) |
| Colour | 0.912 ms | 0.404 ms | 0.508 ms (56%) |

That is the changed stage only, not end-to-end frame time. Against a 33 ms budget at 30fps
it is roughly 1–1.5% of the frame; the more useful effect is dropping a ~130 KB bitmap
allocation per frame, about 3.9 MB/s of GC pressure at 30fps. Note the colour path now
costs essentially the same as grayscale — the separate colour rotation pass is gone.

The strided writes this introduces cost less than the pass they replace, so the cache
locality concern did not materialise at this grid size.

`rotationMap` is pure arithmetic with no Android dependency, so unlike the code it replaced
it is covered by JVM unit tests (`RotationMapTest`) which assert it against the previous
implementation verbatim.

### 4. Shared scratch buffer could escape to the UI

Fixed as a consequence of item 11. `rotateColorGridIfNeeded`'s `else ->` branch returned
the live `lumaColorPixels` singleton straight to Compose state, where the next frame would
overwrite it mid-draw.

The colour grid is now written directly into a per-frame `IntArray` sized exactly to the
output, so the shared buffer no longer exists and neither does the function that leaked it.

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

`ExoPlayerFrameCapture.kt:195-196`

`lastDisplayedBitmap?.recycle()` destroys the previous frame's bitmap, which is still the
value of `videoBitmap` state and may not have been drawn yet. It doesn't crash today only
because `AsciiGridPreview` never draws the bitmap — it reads just `width` and `height`,
which stay legal on a recycled bitmap. Anything that starts actually drawing it turns this
into an immediate `Canvas: trying to use a recycled bitmap`. (Before the dead-code sweep
the same reprieve came from `drawSourceImage` being `false` at every call site.)

---

## ⬜ Open — inefficiencies

### 9. `setPixels` → `getPixels` round trip

`ImageProcessor.kt:201` → `AsciiArt.kt:53-54`

On the video path, `processBitmap` writes `bitmapOutputPixels` into a fresh bitmap, then
`toAsciiText` immediately reads that same data back out into a newly allocated
`IntArray(width*height)`. Passing the int array (or a grayscale `ByteArray`) directly to
the ASCII mapper skips two copies and a per-frame allocation.

---

## ⬜ Open — dead code and simplification

- **`VideoFilePlayer.kt:59, 68-75, 104-131`** — `exoPlayer` as `mutableStateOf` set from inside
  `DisposableEffect` forces an extra recomposition round-trip that every downstream effect then
  has to null-guard; `remember { ExoPlayer.Builder(context).build() }` removes the nullability
  and the guards. The three `rememberUpdatedState` setter wrappers are also unnecessary — a
  lambda capturing a `MutableState` delegate stays valid across recomposition.
- **Hardcoded UI strings** — "Live Camera", "Video File", "Load", "No video loaded…", "Waiting for
  video frames…", and the Play/Pause/Restart `contentDescription`s bypass `strings.xml`, which the
  rest of the screen uses consistently.
- **`READ_EXTERNAL_STORAGE` in the manifest** — the app uses SAF (`OpenDocument` +
  `takePersistableUriPermission`), which grants per-URI access. The permission is never requested
  at runtime and grants nothing on API 24+.
- **ExoPlayer 2.19.1** is end-of-life; every remaining usage warns as deprecated (`ExoPlayer`,
  `MediaItem`, `Player`). `androidx.media3` is the maintained successor. The item 2 fix removed
  the `StyledPlayerView` usage, so the migration surface is now `ExoPlayerFrameCapture` and
  `VideoFilePlayer` only.

---

## ⬜ Open — test coverage

`AsciiArt` gained instrumented coverage in PR #20 (`AsciiArtInstrumentedTest`, 8 tests):
charset ordering, the intensity→glyph mapping and the row stride. `FrameQueueStateTest` is
down to 10 tests after the dead-code sweep and now covers only code the app actually runs.
Camera frame rotation used to be untestable Android code; extracting `rotationMap` as pure
arithmetic put it under JVM unit test (`RotationMapTest`, 5 tests). Still uncovered:

- **`ImagePreview`** — no tests at all, despite now carrying both the item 1 and item 8 fixes,
  neither of which was verified by anything but reading. A Compose `captureToImage()` test
  asserting a white input pixel renders white would close the gap, and would have caught the
  original `268b241` regression.
- **`ImageProcessor.processLumaFrame` / `processBitmap`** — the loops themselves are untested;
  only the rotation mapping they now use is. Both need an `ImageProxy` or a real `Bitmap`.
- **`AsciiGridPreview`** — the draw loops are untested; item 7 pinned the contract they rely
  on, not the drawing.
- **`ExampleUnitTest` / `ExampleInstrumentedTest`** — untouched template boilerplate.

---

## Build health

`./gradlew :app:compileDebugKotlin` succeeds. All remaining warnings are deprecations:
ExoPlayer 2.x throughout, `TabRow` (replaced by `PrimaryTabRow`/`SecondaryTabRow`), and the
edge-to-edge window APIs in `MainActivity` (`statusBarColor`, `navigationBarColor`,
`isStatusBarContrastEnforced`).
