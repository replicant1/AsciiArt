# Code review — 4 August 2026

Full read of the ~1,800 lines of Kotlin under `app/src/main`, plus the Gradle config,
manifest and tests.

**11 of the 12 numbered items are fixed.** Inefficiencies 9 and 15 remain, along with
3 entries on the simplification list. No defects are open.

Four findings arrived after the original review and sit outside its numbering: the
inverted ASCII glyph density, found while writing tests for item 6; item 13, the cost of
`toAsciiText`'s per-pixel mapping, found while measuring item 9; item 14, contrast not
reaching colour output, found by tracing what the grayscale bitmap is used for; and item 15,
split out of 14. Items 13 and 14 and the density bug are fixed; 15 is open.

Numbering is otherwise preserved from the original review so items stay traceable across
the fixes, which is why the fixed list below is not in numeric order.

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

### Hardcoded UI strings extracted

Nine user-visible strings bypassed `strings.xml`, which the rest of the screen already used
consistently: the two tab labels, the Load button, the "no video loaded" and "waiting for
video frames" placeholders, and four `contentDescription`s — Restart, Play, Pause and the
processed-image preview. The last four matter beyond translation, since they are what a
screen reader announces.

The Load button's label was `"  Load"`, using two leading spaces to separate it from its
icon. That could not move into a resource as-is — aapt strips leading and trailing
whitespace from string values unless they are quoted — so the spacing is now a
`Spacer(Modifier.width(8.dp))`, which is what it should have been.

### Composable-local constants hoisted to file scope

`AsciiGridPreview` declared `CACHE_CELL_WIDTH`, `CACHE_CELL_HEIGHT`, `CACHE_CHAR_WIDTH`,
`CACHE_BASELINE_OFFSET` and `TEXT_SIZE_CELL_FRACTION` as `SCREAMING_CASE` `val`s inside the
composable body — constants re-declared on every recomposition and flagged by the IDE for
violating Kotlin's local-variable naming convention.

They are now `private const val` at file scope, alongside the comment explaining the
`textMetricsCache` slot layout and the empirically chosen 0.92 text-height fraction. The
cache array's size comes from a `TEXT_METRICS_CACHE_SLOTS` constant rather than a bare `4`,
so the slot indices and the allocation cannot drift apart.

### 3. Bitmap recycled while still held in Compose state

`ExoPlayerFrameListener` kept the previous frame's processed bitmap in `lastDisplayedBitmap`
and called `recycle()` on it as soon as the next frame arrived, while that bitmap was still
the value of `videoBitmap` in Compose state.

**The original finding recorded this as safe-for-now, and that stopped being true.** It read:
"doesn't crash today only because `AsciiGridPreview` never draws the bitmap — it reads just
`width` and `height`." That held until item 2 landed. Since then the video tab's Image mode
renders `Image(bitmap.asImageBitmap())` — the same bitmap, drawn directly — so from that
point there was a real window in which the UI thread could record a display list referencing
a bitmap that the next frame then recycled, ~60 ms later. A race rather than a certainty,
but no longer theoretical.

Both `recycle()` sites are gone and `lastDisplayedBitmap` with them. The processed bitmap's
lifetime now belongs to the garbage collector, which is what `CameraFrameAnalyzer` has always
done with the equivalent bitmaps on the camera path — its comment says so explicitly.

The capture pool is untouched and still recycled in `release()`. Those bitmaps have exactly
one owner at a time and are never handed to Compose, so ending their lifetime is ours to do.

The cost is one ~130 KB bitmap per frame reaching the collector slightly later than before.
Item 15 would remove most of those allocations outright, which is the better answer than
recycling something that is still being read.

### 14. Contrast was not applied to colour output

Not in the original review — found by tracing what the grayscale bitmap is used for when
Colour is on. It turns out that in Colour + Image mode nothing reads its pixels at all:
`ImagePreview` builds its bitmap from `asciiColors` and takes only `width`/`height` from it.

`processLumaFrame` computed a contrast-adjusted luma for the grayscale path but passed the
**raw** luma to the colour path — `yuvToArgb(gray, u, v)` rather than the adjusted value.
So contrast reached only the array that Image mode never displays. In Colour + ASCII mode
it still selected the glyph, via `toAsciiText` on the adjusted bitmap, but not the colour
that glyph was tinted with.

The colour path now takes `contrastAdjustedGray`. Chroma is deliberately left alone, so
contrast changes brightness separation without shifting hue or saturation — the intended
behaviour is that the same contrast-adjusted luma drives both colour and glyph selection.

Measured on a Pixel 3 against a static scene, sampling luminance across the preview area,
with grayscale mode as a positive control:

| Mode | Luminance stddev, contrast min → max |
| --- | --- |
| Grayscale (control) | 14.62 → 111.24 (+661%) |
| Colour, before | 81.77 → 81.76 (0%) |
| Colour, after | 17.08 → 116.50 (+582%) |

Two things this fix does **not** address, both still open:

- The video pipeline looks like it has the same gap. `processBitmap` copies the source
  pixels into `colorPixels` verbatim while applying contrast only to the grayscale output.
  Unverified — it needs a video file loaded — and the fix would differ, since that path has
  RGB rather than YUV in hand.
- In Colour + Image mode the grayscale bitmap is still built and discarded. Now item 15.

Note that fixing this *narrowed* item 15: before, the per-pixel contrast arithmetic was
surplus in Colour + Image mode too, because nothing downstream consumed it. It now feeds
`yuvToArgb`, so only the bitmap itself is wasted.

### 13. `toAsciiText`'s per-pixel mapping cost 4.4 ms per frame

Not in the original review — found while measuring item 9, which turned out to be a small
part of a much larger cost in the same function.

Two things happened for every one of the 32,400 pixels in a frame: a float multiply,
divide, `roundToInt` and `coerceIn` (plus re-reading `.size` and `.lastIndex` inside the
loop) to recompute one of only 256 possible answers; and an interface dispatch plus an
unbox on every charset access, because the sorted charset was a `List<Char>` — that is
`List<java.lang.Character>`.

Intensity is a byte, so the whole mapping is a 256-entry table. `glyphForIntensity` is now
a `CharArray(256)` built once alongside the sorted charset, and the loop body is a single
array read.

Isolated on a Pixel 3 at the real grid size (135x240), median of 300 runs after warmup,
all variants reading from the same `IntArray` so the item 9 round trip is excluded:

| Variant | Time |
| --- | --- |
| A — as written: float arithmetic + `List<Char>` | 4.435 ms |
| B — float arithmetic, `CharArray` | 2.468 ms |
| C — index lookup table, `List<Char>` | 1.809 ms |
| D — single glyph lookup table (`CharArray(256)`) | 1.019 ms |
| E — floor: loop and `StringBuilder` only, no mapping | 0.992 ms |

Removing the boxing alone (A→B) saved ~2.0 ms; removing the float arithmetic alone (A→C)
saved ~2.6 ms. They overlap, so both together (A→D) saved 3.4 ms, not 4.6. D landing within
noise of E means the mapping is now effectively free and what remains is the loop and
`StringBuilder` themselves.

End to end, timing the real `toAsciiText` against a replica of the previous implementation
in the same run, on the same bitmap:

| | Time |
| --- | --- |
| Before | 6.085 ms |
| After | 1.368 ms |

**4.7 ms saved per frame, a 78% reduction** — about 14% of a 30fps frame budget, on every
ASCII frame on both tabs. The harness asserted the two implementations produce
byte-identical output before timing anything, and the instrumented tests independently
confirm it: they recover the charset *through* `toAsciiText` and assert glyph ordering,
both polarity endpoints and ink monotonicity across the ramp.

This changes the balance of item 9. The `getPixels` round trip measured 0.32 ms, which was
~5% of `toAsciiText` before and is now ~23% of it.

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

## ⬜ Open — inefficiencies

### 15. The grayscale bitmap is built and discarded in Colour + Image mode

`ImageProcessor.kt:155-162` (camera), `ImageProcessor.kt:205-207` (video)

Split out of item 14, where it was noted in passing.

With Colour on and the display in Image mode, nothing reads the grayscale bitmap's pixels.
`ImagePreview` builds its own bitmap from `asciiColors` and takes only `width` and `height`
off the grayscale one — two integers, from a full-size image rebuilt every frame.

Per frame at a 135x240 grid the surplus work is:

- packing and storing 32,400 ARGB ints into the luma output buffer;
- a `Bitmap.createBitmap` of ~130 KB, roughly 3.9 MB/s of allocation at 30fps;
- a `setPixels` copy of those 32,400 ints, measured at ~0.03 ms.

Both pipelines have the shape — `processLumaFrame` for the camera, `processBitmap` for
video.

Two things are **not** part of this item:

- **The contrast arithmetic is still needed.** Since item 14, `contrastAdjustedGray` feeds
  `yuvToArgb`, so it is required for the colour output in every mode. Only the grayscale
  bitmap is surplus, not the arithmetic that would have filled it.
- **Colour + ASCII mode is unaffected.** There `toAsciiText` reads the bitmap's pixels to
  choose glyphs, so it is doing real work even though the bitmap is never displayed.

#### What a fix would look like

The bitmap is doing three unrelated jobs, which is why it looks wasteful — it is being used
as a transport, not only as an image:

1. **Carrying pixels** to `toAsciiText`, which immediately reads them back out with
   `getPixels` — that is item 9.
2. **Carrying grid dimensions** to the UI. `AsciiGridPreview` reads `.width`/`.height`
   thirteen times and reads pixels zero times.
3. **Being the thing drawn** — Image mode with Colour off.

Only job 3 needs a `Bitmap`. Remove jobs 1 and 2 and it is needed in exactly one of four
combinations, and in that one it is not waste at all:

| Display | Colour | Bitmap needed | Why |
| --- | --- | --- | --- |
| Image | off | yes | it *is* the displayed image |
| Image | on | no | the colour bitmap is displayed instead |
| ASCII | off | no | glyphs come from the pixel array; the UI needs only dimensions |
| ASCII | on | no | same |

Full-size allocations per frame at a 135x240 grid, ~130 KB each:

| Display | Colour | Now | With the fix |
| --- | --- | --- | --- |
| Image | off | 1 bitmap | 1 bitmap (already optimal) |
| Image | on | 2 bitmaps + 1 array | 1 bitmap + 1 array |
| ASCII | off | 1 bitmap + 1 array | none |
| ASCII | on | 1 bitmap + 2 arrays | 1 array |

ASCII mode goes from ~260 KB per frame to no pixel allocations at all — about 7.8 MB/s of
GC pressure at 30fps — plus the ~0.35 ms of `setPixels` and `getPixels` those lines cost.
The ASCII `String` itself remains; that is a separate ~65 KB.

The shape that gets there:

```kotlin
data class FrameProcessingResult(
    val displayBitmap: Bitmap?,   // null unless Image mode
    val asciiColors: IntArray?,
    val gridWidth: Int,
    val gridHeight: Int,
)
```

`ImageProcessor` picks the source once — grayscale or colour — rather than the UI
re-deriving it, which makes this a simplification as well as an efficiency:
`ImagePreview(bitmap, modifier)` loses its `colorEnabled`/`asciiColors` branch entirely, and
`AsciiGridPreview(gridWidth, gridHeight, …)` loses a `Bitmap` parameter whose pixels it never
read, so its signature stops implying otherwise.

It does not work as a single change. The dependencies run in order:

1. **Item 3** — mandatory, since the fix makes the returned bitmap definitively the drawn
   one. Now done.
2. **Item 9** — `toAsciiText` takes pixels and dimensions. This is what frees ASCII mode from
   needing a bitmap at all, and is where most of the benefit above comes from.
3. **Item 15** — nullable display bitmap, explicit dimensions, UI signatures follow.

Roughly six files, and the instrumented tests move to the new `toAsciiText` signature. The
cheap alternative — skip the bitmap only in Colour + Image and leave everything else — is
worth perhaps a fifth of the above, because it misses ASCII mode entirely.

### 9. `setPixels` → `getPixels` round trip

`ImageProcessor.kt:206` → `AsciiArt.kt:60-61`

On the video path, `processBitmap` writes `bitmapOutputPixels` into a fresh bitmap, then
`toAsciiText` immediately reads that same data back out into a newly allocated
`IntArray(width*height)`. Passing the int array (or a grayscale `ByteArray`) directly to
the ASCII mapper skips two copies and a per-frame allocation.

---

## ⬜ Open — dead code and simplification

- **`VideoFilePlayer.kt:63, 72-79, 108-135`** — `exoPlayer` as `mutableStateOf` set from inside
  `DisposableEffect` forces an extra recomposition round-trip that every downstream effect then
  has to null-guard; `remember { ExoPlayer.Builder(context).build() }` removes the nullability
  and the guards. The three `rememberUpdatedState` setter wrappers are also unnecessary — a
  lambda capturing a `MutableState` delegate stays valid across recomposition.
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

- **`ImagePreview`** — no tests at all, despite carrying both the item 1 and item 8 fixes. Both
  have since been confirmed by eye on a Pixel 3, in grayscale and in colour, but by screenshot
  rather than by anything that would catch a regression. A Compose `captureToImage()` test
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
