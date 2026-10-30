package meteorbot.core

import kotlin.random.Random

private const val STALE_MS = 100L
private const val END_AFTER_MS = 1000L    // non-game screen this long after gameplay = run over

/**
 * One run: frames in, strokes out. Call [onFrame] from a single thread.
 * Ends the run (calls onStop once) when the game screen has been gone for a second.
 */
class RunController(
    rng: Random,
    private val dispatch: (Stroke) -> Unit,
    private val onStop: () -> Unit,
    /** Drops any queued or running swipe without touching the screen. */
    private val cancel: () -> Unit = {},
) {
    val detector = Detector()
    val tracker = Tracker()
    val planner = Planner(rng)

    @Volatile var masks: List<Box> = emptyList()
    @Volatile var strokeBusyUntilMs = 0L

    private var gameSeen = false
    private var otherSinceMs = -1L
    private var stopped = false
    private var inFlight: Stroke? = null

    fun onFrame(argb: IntArray, w: Int, h: Int, frameTimeMs: Long, nowMs: Long) {
        if (stopped || nowMs - frameTimeMs > STALE_MS) return
        onDetected(detector.detect(argb, w, h, frameTimeMs, masks), nowMs)
    }

    fun onDetected(f: Frame, nowMs: Long) {
        if (stopped) return
        val tracks = tracker.update(f)
        if (f.state != ScreenState.GAME) {
            if (!gameSeen) return
            if (otherSinceMs < 0) {
                // Shade pulled down or app switched: never let a swipe land on other UI.
                otherSinceMs = f.timeMs
                cancel()
                inFlight = null
                strokeBusyUntilMs = 0
            }
            if (f.timeMs - otherSinceMs >= END_AFTER_MS) { stopped = true; onStop() }
            return
        }
        gameSeen = true
        otherSinceMs = -1

        planner.allowMisses(f.planetGrey, tracker.missed)
        if (nowMs < strokeBusyUntilMs) {
            val s = inFlight ?: return
            // Re-check every frame: new bombs appear, and known ones reveal their real velocity.
            if (!strokeIsSafe(s, tracks.filter { it.kind == Kind.BOMB }, f.aspect, fromMs = nowMs)) abort(tracks, f.aspect, nowMs)
            return
        }
        val s = planner.plan(tracks, nowMs, f.aspect, masks) ?: return
        inFlight = s
        strokeBusyUntilMs = s.startMs + s.segments.sumOf { it.durationMs }
        dispatch(s)
    }

    /** Replaces the gesture in flight with a 1 ms tap in the corner farthest from every bomb. */
    private fun abort(tracks: List<Track>, aspect: Float, nowMs: Long) {
        val bombs = tracks.filter { it.kind == Kind.BOMB }
        // Bottom corners only: the game's close button is at the top left.
        val corners = listOf(Vec(.05f, .95f * aspect), Vec(.95f, .95f * aspect))
            .filter { c -> masks.none { it.contains(c) } }
        val spot = corners.maxByOrNull { c -> bombs.minOfOrNull { (it.pos - c).len() } ?: Float.MAX_VALUE } ?: return
        val tap = Stroke(nowMs + planner.latencyMs, listOf(Segment(spot, spot, spot, 1)), emptyList())
        inFlight = null
        strokeBusyUntilMs = tap.startMs + 1
        dispatch(tap)
    }
}
