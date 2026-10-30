# Meteor Bot: Design

Date: 2026-10-30
Status: awaiting review

## Goal

An Android app that plays the meteor-slicing rewards mini-game inside the user's
payments app, on the phone itself, by reading screen pixels and performing swipes.
It plays one run per tap of a floating Start button and plays like a skilled human.

It never touches the payments app's code, network traffic or API. Its only inputs
are screen pixels and its only output is touch gestures.

## Success criteria

- In a normal run the bot slices most meteors and never swipes a bomb.
- It ends each run with a deliberate miss count between 1 and 4, which is under the game's miss limit.
- It stops by itself when the result screen appears.
- The same APK works on Android 9 (API 28) through Android 16+, at any screen resolution.

## Out of scope

- Ads, the Skip button, "Play next" and looping runs. The user handles these.
- Any reverse-engineering or network interaction with the payments app.
- A calibration screen. Add one only if detection fails on a real phone.
- Laptop, adb or root dependency at runtime.

## Game facts (from Slice.mp4, 386x850, ~86 s)

- **Meteor:** a white round head with an orange-to-pink tail pointing up. Early in a run
  it falls about 1.3 screen heights per second, so it stays on screen for about 0.75 s.
  Speed varies, so it is measured per meteor and never assumed.
- **Bomb:** an orange hexagon with a dark skull face, about the size of a meteor head.
  Bombs fall slower than meteors, often outnumber them, and spawn next to them.
- **Debris:** white crescents and dots left after a slice. They have no tail and are not targets.
- **HUD:** a score pill at top centre and an "x" at top left.
- **Planet:** purple at the bottom of the screen. It turns grey as meteors are missed,
  which is the damage indicator. Grey does not mean the run is over.
- **Run end:**
  - orange "You lost! You swiped on a bomb"
  - orange "You lost! You missed too many meteors"
  - dark "Yay! You won ₹N"
- **Non-game screens:** a black-hole intro, ad screens (black, with Skip), and a white flash after a bomb hit.

## Architecture

A single Kotlin app with no OpenCV and no third-party runtime libraries.

| Unit | Kind | Responsibility |
|---|---|---|
| `SetupActivity` | Activity | Requests overlay permission, links to Accessibility settings, requests MediaProjection consent, starts `CaptureService` |
| `CaptureService` | Foreground service (`mediaProjection` type) | Pulls frames from MediaProjection into an `ImageReader` at 1/3 resolution and hands each frame to the pipeline on a single background thread |
| `Detector` | Pure Kotlin | `(argb: IntArray, w, h) -> Frame(state, meteors, bombs, planetGrey)`. All positions and sizes are fractions of frame width |
| `Tracker` | Pure Kotlin | Matches objects across frames by nearest predicted position and keeps a velocity for each track |
| `Planner` | Pure Kotlin | Picks targets, builds blade paths, applies the bomb rule and human-like behaviour, and returns a `Stroke` or nothing |
| `SwipeService` | AccessibilityService | Runs a `Stroke` with `dispatchGesture`, chaining segments using `StrokeDescription.continueStroke` (API 26+) |
| `Overlay` | Inside `SwipeService` | Floating Start/Stop button (`TYPE_ACCESSIBILITY_OVERLAY`). Not touchable outside the button. Its rectangle is masked out of detection |

`Detector`, `Tracker` and `Planner` have no Android imports, so they run on the JVM in unit tests.

### Data flow

```
frame (1/3 res) -> Detector -> Tracker -> Planner -> SwipeService.dispatchGesture
                      |
                      +-- state == RESULT -> stop run
```

Only one stroke is in flight at a time. A new plan while a stroke is running either
extends it with a continued segment or cancels it (see the bomb rule).

## Detection

All thresholds are in HSV, so they don't depend on resolution. Sizes are fractions of frame width.

- **In-game:** purple (hue around 260–290°, medium saturation) covers more than 40% of the frame.
  If not, the state is `IDLE`, or `RESULT` when the frame is mostly orange-red or matches the
  dark win screen after a game was seen. The bot acts only in the `GAME` state.
- **Meteor:** a connected blob of near-white pixels (V > 0.9, S < 0.2) with an orange/pink
  region (hue 330–40°, S > 0.4) directly above it, extending at least one head height.
  The head centre is the target point.
- **Bomb:** a connected blob of orange (hue 20–45°, S > 0.6) with dark pixels (V < 0.3)
  inside its bounding box. Its radius is measured from the blob.
- **Planet grey ratio:** the fraction of the bottom 20% of the frame that is low-saturation
  grey instead of purple. It drives the miss safety cap.
- **Masks:** the top 8% (HUD) and the overlay button rectangle are ignored.
- **Blob finding:** a single-pass connected-component labelling on a grid sampled every 2 pixels of the 1/3-resolution frame.

## Tracking

- Each track keeps its position, velocity (exponentially smoothed) and last-seen time.
- A detection is matched to the nearest track's predicted position within 0.1 frame widths. Otherwise it starts a new track.
- A track is dropped after 150 ms without being seen.
- A meteor track becomes "sliced" when its head disappears while a stroke has just passed through it.
- A meteor that leaves the bottom of the screen unsliced counts as a miss.

## Planning

### Targeting

- Candidates are meteor tracks seen in at least 2 frames, so they have a velocity.
- The bot aims at the predicted position at time `t_land = now + reaction_delay + pipeline_latency (60 ms default)`.
- The planner picks the candidate that will reach the bottom soonest among those with a safe path.
- When several are safe and close together, it chains up to 3 into one continued stroke.

### Blade path

- A short segment, 0.12 frame widths long, crosses the predicted head roughly perpendicular to the meteor's motion.
- Swipe duration is 40–80 ms per segment.
- Wobble: each segment's midpoint is offset sideways by up to 15% of its length, which makes it a gentle curve.

### Bomb rule (hard constraint, checked last, never relaxed)

- For every bomb track, predict its position across the whole time window of the stroke.
- Every point of the stroke must stay more than `1.5 × bomb diameter` from every predicted bomb position.
- If no orientation of the segment (tried every 20°) passes, the meteor is skipped.
- If a bomb appears that wasn't tracked before, and its predicted position comes within the
  clearance of the stroke in flight, the stroke is cancelled by dispatching a 1 ms tap off
  in a bomb-free corner of the playfield. That replaces the current gesture and lifts the finger.

### Human-like behaviour

- **Reaction delay:** random 80–200 ms per target, uniformly distributed. Adjustable in the overlay.
- **Miss budget:** random integer 1–4, picked when Start is tapped.
  - Deliberate misses happen at random moments spread through the run, and only on easy, isolated meteors.
  - Forced misses (from the bomb rule) count against the same budget. When the budget is used up, deliberate misses stop.
  - **Safety cap:** when the planet grey ratio rises above 15%, deliberate misses stop completely, whatever budget is left.
- **Wobble:** on by default.

## Permissions and Android versions

- `SYSTEM_ALERT_WINDOW` is not needed, because the overlay is an accessibility overlay.
- `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MEDIA_PROJECTION` (API 34+).
- `POST_NOTIFICATIONS` (API 33+) for the foreground service notification.
- An AccessibilityService config with `canPerformGestures="true"`.
- On Android 14+ the system asks for screen-capture consent on every start. This can't be avoided.
- minSdk 28, targetSdk 35, compileSdk 35.

## Error handling

- If MediaProjection is revoked or stopped by the system, the bot stops the run and resets the overlay button to Start.
- If the accessibility service is not enabled, the Start button opens Accessibility settings instead of starting.
- If the frame pipeline runs late (frame older than 100 ms), the frame is dropped instead of planned on.
- If a gesture is rejected (`onCancelled`), no retry happens, and the next frame plans again.

## Testing

- **Unit tests (JVM, JUnit):** `Detector`, `Tracker` and `Planner`.
- **Fixtures:** frames extracted from `Slice.mp4` with ffmpeg, scaled to 1/3 of a 1080-wide phone (360 wide), and checked in under `app/src/test/resources/frames/`.
  - For each fixture, the expected meteor and bomb counts and approximate positions are labelled by hand in a small JSON file.
  - The detector must find all labelled bombs (recall 100%) and at least 90% of labelled meteors.
  - State classification must be correct on intro, ad, game, flash, lost and won frames.
- **Planner property test:** over thousands of random bomb and meteor layouts, no emitted stroke ever violates the bomb clearance.
- **On-device check:** one real run on the user's phone. Pass means no bomb hit, the run ends normally, and the bot stops.

## Build

- Kotlin and Gradle (wrapper) on the laptop.
- Needs JDK 17 and Android SDK command-line tools (platform 35 and build-tools), about 1–2 GB.
- Output is a debug-signed APK that the user sideloads on each phone.

## Risks

- The payments app may detect an enabled accessibility service and warn or restrict features.
  The first implementation milestone is a do-nothing build with the accessibility service enabled, to test this before any detection work.
- Account action for bot-like play. This is mitigated, not removed, by the human-like behaviour. The user accepts it.
- The game's art may change in an update, and then thresholds need re-tuning from a new recording.
- The game may speed up later in a run beyond the 0.75 s crossing time measured here. The planner skips anything it can't reach safely.
