# Meteor Bot Implementation Plan

**Goal:** An Android APK that plays the meteor-slicing mini-game on the phone, reading screen pixels and swiping, playing one human-like run per Start tap.

**Architecture:** There are two Gradle modules. `:core` is plain Kotlin/JVM: pixel conversion, Detector, Tracker, Planner and RunController, all unit-tested against frames from `Slice.mp4`. `:app` is the Android shell: a MediaProjection capture service feeds frames to `RunController`, and an AccessibilityService runs its strokes with `dispatchGesture` and hosts the floating Start/Stop button.

**Tech Stack:** Kotlin 2.0.21, Gradle 8.10.2 (wrapper), AGP 8.7.3, JDK 17, Android SDK platform 35 / build-tools 35.0.0, JUnit 4.13.2. No other libraries.

**Spec:** `docs/specs/2026-10-30-meteor-bot-design.md`

## Global Constraints

- minSdk 28, targetSdk 35, compileSdk 35.
- No OpenCV and no third-party runtime libraries. `:core` has no Android imports.
- All positions and sizes are in **width units**: x in [0,1], y in [0, aspect], where aspect = height / width. Time is in milliseconds.
- Capture runs at 1/3 of the screen resolution.
- Bomb clearance is `1.5 × bomb diameter` along the whole stroke time window. It is never relaxed.
- Reaction delay is uniform 80–200 ms. The miss budget is a random int 1..4 per run. Deliberate misses stop when planet grey > 0.15.
- Segment length is 0.12 width units, its duration 40–80 ms, and wobble is up to 15% of its length sideways. Orientations are tried every 20°.
- Tracks are matched within 0.1 width units and dropped after 150 ms unseen. Frames older than 100 ms are dropped. Pipeline latency default is 60 ms.
- Package `meteorbot.core` for `:core` and `meteorbot.app` for `:app`. applicationId is `meteorbot.app`.
- **Deviation from the spec (decided here):** `Detector` only classifies `GAME` vs `OTHER`. `RunController` ends the run after `GAME` was seen and then `OTHER` lasts 1000 ms. This covers all three result screens and the bomb flash without classifying each one, and the user's goal ("stops when the run ends") is unchanged.

## Review Focus

1. **Taller phones (20:9 vs the video's ~2.2:1 and 16:9):** detection must still work, because positions are width-relative. Pinned by the `tallerAspectStillDetects` test in Task 3.
2. **Capture buffers with row padding (`rowStride > width*4`) on many phones:** these must not shear the image. Pinned by the `rowPaddingIgnored` test in Task 2.
3. **The floating button sitting over a meteor or a white star:** that area must never be detected or swiped. Pinned by `maskedRegionIgnored` (Task 3) and `strokeAvoidsMask` (Task 5).
4. **A meteor spawning touching a bomb:** the planner must skip it, not graze the bomb. Pinned by `meteorHuggingBombSkipped` (Task 5).
5. **The user pulls down the notification shade or switches apps mid-run:** the bot must stop, not swipe on other UI. Pinned by `nonGameAfterGameStops` (Task 6), since non-game frames end the run.

---

### Task 1: Toolchain, two-module project, and do-nothing probe APK

The deliverable is a debug APK that installs, shows a floating button once its accessibility service is enabled, and does nothing else. The user then checks whether the payments app reacts to an enabled accessibility service. **This is the go/no-go milestone.**

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/wrapper/*`, `gradlew`, `.gitignore`, `local.properties` (gitignored)
- Create: `core/build.gradle.kts`, `core/src/main/kotlin/meteorbot/core/Geometry.kt`
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/swipe_service.xml`, `app/src/main/res/values/strings.xml`
- Create: `app/src/main/kotlin/meteorbot/app/SwipeService.kt`, `app/src/main/kotlin/meteorbot/app/SetupActivity.kt`

**Interfaces:**
- Produces: `data class Vec(val x: Float, val y: Float)` with `plus`, `minus`, `times(Float)`, `len()`, and `fun distToSegment(p: Vec, a: Vec, b: Vec): Float`. Also `data class Box(val l: Float, val t: Float, val r: Float, val b: Float) { fun contains(p: Vec): Boolean }`, all in `Geometry.kt`.

- [ ] **Step 1: Install the toolchain**

Run: `pkexec pacman -S --needed --noconfirm jdk17-openjdk gradle`. Then download `https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip`, unzip it into `~/Android/Sdk/cmdline-tools/latest`, and run `yes | sdkmanager --sdk_root=$HOME/Android/Sdk "platforms;android-35" "build-tools;35.0.0" "platform-tools"`.
Expected: `~/Android/Sdk/platforms/android-35` exists, and `/usr/lib/jvm/java-17-openjdk/bin/java -version` prints 17.

- [ ] **Step 2: Scaffold the modules**

`gradle wrapper --gradle-version 8.10.2` (run with JAVA_HOME at JDK 17). `:core` uses the `org.jetbrains.kotlin.jvm` plugin, JVM toolchain 17, and JUnit 4.13.2. `:app` uses `com.android.application` and `org.jetbrains.kotlin.android`, depends on `project(":core")`, and needs no AndroidX. `local.properties` contains `sdk.dir=/home/adminw/Android/Sdk`.

- [ ] **Step 3: Write the Geometry test, then the code**

`core/src/test/kotlin/meteorbot/core/GeometryTest.kt`:
```kotlin
@Test fun distToSegment() {
    assertEquals(1f, distToSegment(Vec(0f,1f), Vec(-1f,0f), Vec(1f,0f)), 1e-5f)
    assertEquals(1f, distToSegment(Vec(2f,0f), Vec(-1f,0f), Vec(1f,0f)), 1e-5f) // past the endpoint
}
@Test fun boxContains() { assertTrue(Box(0f,0f,1f,1f).contains(Vec(.5f,.5f))); assertFalse(Box(0f,0f,1f,1f).contains(Vec(1.5f,.5f))) }
```
Run `./gradlew :core:test` and confirm it FAILS. Implement it, then run again and confirm it PASSES.

- [ ] **Step 4: Probe app**

The manifest declares `SwipeService` with `BIND_ACCESSIBILITY_SERVICE` and `swipe_service.xml` (`canPerformGestures="true"`, `accessibilityFeedbackType="feedbackGeneric"`, no event types). `SwipeService.onServiceConnected` adds a 56dp round "Start" button as `TYPE_ACCESSIBILITY_OVERLAY` (`FLAG_NOT_FOCUSABLE`, wrap_content, so it only blocks touches on itself). The button is draggable and tapping it does nothing yet. `SetupActivity` (the launcher) has one button that opens `Settings.ACTION_ACCESSIBILITY_SETTINGS`.

- [ ] **Step 5: Build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL, with `app/build/outputs/apk/debug/app-debug.apk` present.

- [ ] **Step 6: Commit**, with the message `feat: project skeleton and accessibility probe APK`.

- [ ] **Step 7: USER CHECKPOINT**

The user installs the APK, enables the "Meteor Bot" accessibility service, opens the payments app and the game, and reports what happens. If the payments app warns, blocks or hides the game, **stop and report**. The rest of the plan depends on this.

---

### Task 2: Frame fixtures and pixel conversion

**Files:**
- Create: `core/src/main/kotlin/meteorbot/core/Pixels.kt`
- Create: `core/src/test/resources/frames/*.png` and `core/src/test/resources/frames/labels.csv`
- Create: `core/src/test/kotlin/meteorbot/core/Fixtures.kt`, `core/src/test/kotlin/meteorbot/core/PixelsTest.kt`

**Interfaces:**
- Produces: `fun rgbaToArgb(src: java.nio.ByteBuffer, w: Int, h: Int, rowStride: Int, pixelStride: Int, out: IntArray)` (Android ImageReader RGBA_8888 to packed ARGB). It also produces `fun hsv(argb: Int, out: FloatArray)`, where out = [h 0..360, s 0..1, v 0..1].
- Produces (test-only): `object Fixtures { fun load(name: String): Triple<IntArray, Int, Int>; val labels: List<Label> }` with `data class Label(val file: String, val state: String, val meteors: List<Vec>, val bombs: List<Vec>)`. Positions are in width units.

- [ ] **Step 1: Extract fixtures**

Use ffmpeg on `/home/adminw/Downloads/Slice.mp4` with `scale=360:-1`, one PNG per timestamp. Take at least 12 frames: 2 intro (black hole), 2 ad (black with Skip), 6 game frames (including the t≈22 s burst with a meteor beside a bomb, and one with a grey planet), 1 lost (orange), and 1 won (dark).

- [ ] **Step 2: Label them**

View each PNG and record in `labels.csv` its state (`GAME`/`OTHER`), each meteor head centre and each bomb centre in width units. Skip objects cut off by the top edge by more than half.

- [ ] **Step 3: Failing tests**

```kotlin
@Test fun rowPaddingIgnored() {
    // 2x2 image, rowStride 16 (8 bytes of padding per row)
    val b = ByteBuffer.allocate(32); /* row0: red, green; row1: blue, white; padding bytes = 0x7F */
    val out = IntArray(4); rgbaToArgb(b, 2, 2, 16, 4, out)
    assertArrayEquals(intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), -1), out)
}
@Test fun hsvOfPurple() { val o = FloatArray(3); hsv(0xFF7B3FE4.toInt(), o); assertEquals(262f, o[0], 2f) }
@Test fun fixturesLoad() { assertTrue(Fixtures.labels.size >= 12); val (px, w, _) = Fixtures.load(Fixtures.labels[0].file); assertEquals(360, w); assertEquals(w * (px.size / w), px.size) }
```
Run `./gradlew :core:test` and confirm it FAILS.

- [ ] **Step 4: Implement**

Implement `Pixels.kt`, and `Fixtures` with `javax.imageio.ImageIO` and a CSV reader for `labels.csv` (`file,state,kind,x,y`, where kind is `-` for frames with no objects.

- [ ] **Step 5: Run `./gradlew :core:test` and confirm it PASSES. Commit** with the message `feat(core): pixel conversion and labelled video fixtures`.

---

### Task 3: Detector

**Files:**
- Create: `core/src/main/kotlin/meteorbot/core/Detector.kt`
- Test: `core/src/test/kotlin/meteorbot/core/DetectorTest.kt`

**Interfaces:**
- Consumes: `Vec`, `Box`, `hsv`, `Fixtures`.
- Produces:
  ```kotlin
  enum class ScreenState { GAME, OTHER }
  data class Blob(val pos: Vec, val radius: Float)
  data class Frame(val timeMs: Long, val aspect: Float, val state: ScreenState,
                   val meteors: List<Blob>, val bombs: List<Blob>, val planetGrey: Float)
  class Detector { fun detect(argb: IntArray, w: Int, h: Int, timeMs: Long, masks: List<Box> = emptyList()): Frame }
  ```

- [ ] **Step 1: Failing tests**

```kotlin
@Test fun classifiesEveryFixture()  // state matches label for 100% of fixtures
@Test fun findsAllBombs()           // every labelled bomb has a detected bomb within 0.04 width units (recall 100%)
@Test fun findsMostMeteors()        // >= 90% of labelled meteors matched within 0.04; false positives <= 1 per frame
@Test fun debrisIsNotAMeteor()      // on the t≈22 s fixture, the white crescent/dot debris yields no meteor
@Test fun tallerAspectStillDetects() // pad a game fixture with 80 rows of its own top background colour at the top: same counts, y shifted by 80/360
@Test fun maskedRegionIgnored()     // a Box covering a labelled meteor removes exactly that meteor
@Test fun greyPlanetMeasured()      // the grey-planet fixture has planetGrey > 0.3, a purple one < 0.05
```
Run `./gradlew :core:test --tests '*DetectorTest*'` and confirm it FAILS.

- [ ] **Step 2: Implement**

The spec's Detection section lists the thresholds (purple > 40% for GAME, the HUD mask on the top 8%, meteor = white blob with orange/pink above it at least one head height, bomb = orange blob with dark pixels inside, planet grey over the bottom 20%). Use connected components on a grid sampled every 2 px, with a flood fill using an `IntArray` stack and no recursion. Ignore blobs with radius < 0.01 width units. If a fixture fails, tune the thresholds and record the final values as named constants at the top of `Detector.kt`.

- [ ] **Step 3: Run the tests and confirm they PASS. Commit** with the message `feat(core): colour-blob detector tuned on video frames`.

---

### Task 4: Tracker

**Files:**
- Create: `core/src/main/kotlin/meteorbot/core/Tracker.kt`
- Test: `core/src/test/kotlin/meteorbot/core/TrackerTest.kt`

**Interfaces:**
- Consumes: `Frame`, `Blob`, `Vec`.
- Produces:
  ```kotlin
  enum class Kind { METEOR, BOMB }
  data class Track(val id: Int, val kind: Kind, val pos: Vec, val vel: Vec /* units per ms */,
                   val radius: Float, val seen: Int, val lastSeenMs: Long) { fun at(tMs: Long): Vec }
  class Tracker { fun update(f: Frame): List<Track>; val missed: Int }
  ```
  `missed` counts meteor tracks dropped while their last y > 0.85 × aspect, meaning they hit the planet.

- [ ] **Step 1: Failing tests**, using synthetic `Frame`s 33 ms apart:

`keepsIdAndEstimatesVelocity` (a meteor moving 0.01/frame down keeps its id, vel.y ≈ 0.01/33 within 20%), `separateKindsNeverMatch` (a bomb and a meteor at the same spot get distinct tracks), `dropsAfter150ms`, `matchRadius` (a jump > 0.1 starts a new track), `countsMissAtBottom` (a meteor last seen at y = 0.9 × aspect then gone 200 ms means missed == 1; one vanishing mid-screen means missed == 0), `atPredicts` (track.at(t + 100) == pos + vel × 100).

- [ ] **Step 2: Run and confirm FAIL. Implement it, with greedy nearest-predicted matching and velocity smoothing `vel = 0.6 × new + 0.4 × old`. Run and confirm PASS. Commit** with the message `feat(core): object tracker with velocity and miss counting`.

---

### Task 5: Planner (targeting and bomb rule)

**Files:**
- Create: `core/src/main/kotlin/meteorbot/core/Planner.kt`
- Test: `core/src/test/kotlin/meteorbot/core/PlannerTest.kt`

**Interfaces:**
- Consumes: `Track`, `Kind`, `Vec`, `Box`, `distToSegment`.
- Produces:
  ```kotlin
  data class Segment(val a: Vec, val mid: Vec, val b: Vec, val durationMs: Long)   // quadratic curve a→b via mid
  data class Stroke(val startMs: Long, val segments: List<Segment>, val targetIds: List<Int>)
  fun strokeIsSafe(s: Stroke, bombs: List<Track>, masks: List<Box> = emptyList()): Boolean
  class Planner(private val rng: kotlin.random.Random, private val latencyMs: Long = 60) {
      fun plan(tracks: List<Track>, nowMs: Long, aspect: Float, masks: List<Box>): Stroke?
  }
  ```
  `strokeIsSafe` samples each segment's curve every 5 ms of its duration and checks the distance to each bomb's `at(sampleTime)` > 1.5 × 2 × radius. It also requires every sample to be inside [0,1] × [0.08 × aspect, aspect] and outside every mask.

- [ ] **Step 1: Failing tests**

```kotlin
@Test fun slicesLoneMeteor()          // one meteor, no bombs: stroke non-null, its first segment passes within head radius of track.at(stroke.startMs + d/2)
@Test fun aimsAhead()                 // fast meteor (vel.y = 0.002/ms): segment crosses the predicted position, not the current one
@Test fun meteorHuggingBombSkipped()  // meteor centre 0.03 from a bomb centre of radius 0.03: plan returns null
@Test fun picksLowestFirst()          // two safe meteors: targetIds[0] is the one with larger y
@Test fun chainsUpTo3()               // 5 safe meteors in a cluster: segments.size <= 3, all safe
@Test fun strokeAvoidsMask()          // meteor under a mask Box: null
@Test fun neverUnsafeProperty()       // 5,000 random layouts (0–6 meteors, 0–8 bombs, random velocities, seeded): every non-null stroke passes strokeIsSafe
```
Run them and confirm they FAIL.

- [ ] **Step 2: Implement**

For each candidate meteor (`seen >= 2`, ordered by largest y), predict `p = at(nowMs + latencyMs + delay)`. Try orientations perpendicular to vel ± k × 20° and take the first segment of length 0.12 centred on p that passes `strokeIsSafe`. Chain the next candidate only if its own segment is safe and starts within 0.25 width units of the previous segment's end. In this task, delay = 0 and wobble = 0 (Task 6 adds them). `plan` must return only strokes for which `strokeIsSafe` is true. That makes the property test hold by construction.

- [ ] **Step 3: Run and confirm PASS. Commit** with the message `feat(core): planner with predictive aiming and strict bomb clearance`.

---

### Task 6: Human-like play and RunController

**Files:**
- Modify: `core/src/main/kotlin/meteorbot/core/Planner.kt`
- Create: `core/src/main/kotlin/meteorbot/core/RunController.kt`
- Test: `core/src/test/kotlin/meteorbot/core/HumanTest.kt`, `core/src/test/kotlin/meteorbot/core/RunControllerTest.kt`

**Interfaces:**
- Planner gains `var reactionMs: IntRange = 80..200`, `var wobble = true`, `val missBudget: Int` (rng 1..4, set at construction), `fun allowMisses(planetGrey: Float, missedSoFar: Int)` (called each frame), and a private set of deliberately ignored track ids.
- Produces:
  ```kotlin
  class RunController(rng: Random, private val dispatch: (Stroke) -> Unit, private val onStop: () -> Unit) {
      val detector: Detector; val tracker: Tracker; val planner: Planner
      var masks: List<Box>
      var strokeBusyUntilMs: Long
      fun onFrame(argb: IntArray, w: Int, h: Int, frameTimeMs: Long, nowMs: Long)
  }
  ```
  `onFrame` works like this:
  - Drop the frame if `nowMs - frameTimeMs > 100`.
  - detect, then track. If state is GAME, mark game seen.
  - If game seen and state has been OTHER for ≥ 1000 ms, call `onStop()` once.
  - If not busy, `planner.plan` and then `dispatch`.
  - If busy and a bomb track with `seen == 1` makes the in-flight stroke unsafe, dispatch an abort stroke: one 1 ms segment at the safest corner of the playfield, `targetIds` empty.

- [ ] **Step 1: Failing tests**

`HumanTest`:
- `reactionDelayInRange`: over 1,000 plans, startMs − nowMs − latency is in 80..200, with a mean of 140 ± 10.
- `missBudgetRange`: over 1,000 seeds the values are only 1..4, and all four occur.
- `deliberateMissesRespectBudget`: in a simulated run of 200 easy isolated meteors, ignored ids is between 1 and missBudget.
- `greyCapStopsMisses`: once allowMisses(0.2, 0) has been called, no new ignores.
- `forcedMissesCount`: allowMisses(0f, missBudget) means no new ignores.
- `wobbleWithinLimit`: the mid offset from the chord ≤ 0.15 × 0.12.
- `strokesStillSafe`: rerun the Task 5 property test with delay and wobble on.

`RunControllerTest`:
- `staleFrameDropped`
- `nonGameAfterGameStops`: feed the GAME fixture, then OTHER fixtures for 1,100 ms, and onStop is called exactly once.
- `otherBeforeGameDoesNotStop`: intro frames for 5 s, and onStop is never called.
- `dispatchesOnGameFixture`: two consecutive synthetic frames with one moving meteor produce one dispatch.
- `newBombAborts`: while busy, a fresh bomb on the stroke path triggers an abort dispatch with empty targetIds.

Run them and confirm they FAIL.

- [ ] **Step 2: Implement**

A deliberate miss is picked when `allowMisses` permits it (grey ≤ 0.15 and ignored + missedSoFar < budget). The chosen meteor is the first plan target that has no other meteor or bomb within 0.2 width units, with probability 0.08. The meteor is added to ignored ids and the planner moves to the next candidate. Wobble offsets `mid` by `rng` in ±0.15 × length perpendicular to the chord. Segment duration is `rng` in 40..80.

- [ ] **Step 3: Run `./gradlew :core:test` and confirm everything PASSES. Commit** with the message `feat(core): human-like play and run controller`.

---

### Task 7: Android wiring (capture, gestures, overlay)

**Files:**
- Create: `app/src/main/kotlin/meteorbot/app/CaptureService.kt`, `app/src/main/kotlin/meteorbot/app/Bot.kt`
- Modify: `app/src/main/kotlin/meteorbot/app/SwipeService.kt`, `app/src/main/kotlin/meteorbot/app/SetupActivity.kt`, `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `RunController`, `Stroke`, `Segment`, `Box`, `rgbaToArgb`.
- Produces: `object Bot { var swipe: SwipeService?; var capture: CaptureService?; var controller: RunController? }`. It holds same-process references. Mark it `// shortcut: process-global refs, fine for a single-process app`.

- [ ] **Step 1: Manifest**

Add `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION` and `POST_NOTIFICATIONS`. Declare `CaptureService` with `android:foregroundServiceType="mediaProjection"`.

- [ ] **Step 2: Start flow**

- Overlay Start tap: if the run is not active, launch `SetupActivity` with the `start` extra.
- `SetupActivity`: request `POST_NOTIFICATIONS` on API 33+, then `MediaProjectionManager.createScreenCaptureIntent()`. On RESULT_OK, start `CaptureService` (foreground) with the result code and data, then finish.
- `CaptureService.onStartCommand`:
  - Call `startForeground` with a notification and the mediaProjection type (on API 29+ use the 3-arg overload) **before** `getMediaProjection`.
  - Register a `MediaProjection.Callback` (onStop means stop the run).
  - Create an `ImageReader(w/3, h/3, RGBA_8888, 2)` and a `VirtualDisplay` on a `HandlerThread`.
  - Create `RunController(Random.Default, dispatch = { Bot.swipe?.perform(it) }, onStop = ::stopRun)`.
- In `onImageAvailable`: `acquireLatestImage`, then `rgbaToArgb`. Pass `image.timestamp / 1_000_000` as frameTimeMs and `SystemClock.elapsedRealtimeNanos() / 1_000_000` as nowMs (both elapsedRealtime-based).
- Overlay mask: SwipeService publishes the button rect converted to width units into `controller.masks` on every drag.
- Stop tap or `stopRun`: release the VirtualDisplay, ImageReader and projection, `stopSelf`, and set the overlay label back to "Start".

- [ ] **Step 3: Gesture execution**

`SwipeService.perform(stroke: Stroke)` posts to the main handler at `stroke.startMs` (elapsedRealtime timebase). For each segment it builds a `Path` (`moveTo(a)`, `quadTo(mid, b)`) in screen pixels (x × screenWidth, y × screenWidth, using `WindowManager.currentWindowMetrics` on API 30+ and `getRealMetrics` below that). Segment 1 is a `StrokeDescription(path, 0, d, willContinue = n > 1)`. Each later segment is `prev.continueStroke(path, 0, d, willContinue = !last)`, dispatched from the previous `onCompleted`. The gap between segments is a straight line from the previous b to the next a. Set `controller.strokeBusyUntilMs` to the total end time. On `onCancelled` clear busy and don't retry.

- [ ] **Step 4: Build**

Run: `./gradlew :core:test :app:assembleDebug`
Expected: all tests PASS and BUILD SUCCESSFUL.

- [ ] **Step 5: Commit** with the message `feat(app): capture service, gesture execution, start/stop overlay`.

---

### Task 8: On-device run (user)

- [ ] **Step 1:** The user installs the new APK (`adb install -r` if the phone is connected, or by copying the file over), enables the service, opens the game and taps Start, then taps "Start now" on the capture prompt.
- [ ] **Step 2:** Pass means no bomb hit, the run ends normally with 1–4 misses, the button returns to "Start", and the capture notification is gone.
- [ ] **Step 3:** If detection is off on that phone, the user records a new screen recording. Add frames from it to the fixtures, re-tune in Task 3's tests, rebuild, and commit with the message `fix(core): retune thresholds for <phone>`.
