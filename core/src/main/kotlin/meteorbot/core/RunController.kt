package meteorbot.core

private const val STALE_MS = 100L
private const val END_AFTER_MS = 1000L    // non-game screen this long after gameplay = run over

/**
 * One run: frames in, strokes out. Call [onFrame] from a single thread.
 * Ends the run (calls onStop once) when the game screen has been gone for a second.
 */
class RunController(
    private val dispatch: (Stroke) -> Unit,
    private val onStop: () -> Unit,
    /** Drops any queued or running swipe without touching the screen. */
    private val cancel: () -> Unit = {},
    /** One line per event, for the run log file. */
    private val log: (String) -> Unit = {},
) {
    val detector = Detector()
    val tracker = Tracker()
    val planner = Planner()

    @Volatile var masks: List<Box> = emptyList()

    // Counters for the on-screen stats line.
    @Volatile var frames = 0; private set
    @Volatile var stale = 0; private set
    @Volatile var strokes = 0; private set
    @Volatile var aborts = 0; private set
    @Volatile var lastGrey = 0f; private set
    @Volatile var strokeBusyUntilMs = 0L

    private var gameSeen = false
    private var otherSinceMs = -1L
    private var stopped = false
    private var inFlight: Stroke? = null

    fun onFrame(argb: IntArray, w: Int, h: Int, frameTimeMs: Long, nowMs: Long) {
        if (stopped) return
        frames++
        if (nowMs - frameTimeMs > STALE_MS) { stale++; log("$nowMs STALE lag=${nowMs - frameTimeMs}"); return }
        onDetected(detector.detect(argb, w, h, frameTimeMs, masks), nowMs)
    }

    fun onDetected(f: Frame, nowMs: Long) {
        if (stopped) return
        val tracks = tracker.update(f)
        if (f.state != ScreenState.GAME) {
            if (!gameSeen) return
            if (otherSinceMs < 0) {
                log("${f.timeMs} SCREEN ${f.state} missed=${tracker.missed}")
                // Shade pulled down or app switched: never let a swipe land on other UI.
                otherSinceMs = f.timeMs
                cancel()
                inFlight = null
                strokeBusyUntilMs = 0
            }
            if (f.timeMs - otherSinceMs >= END_AFTER_MS) {
                log("${f.timeMs} END strokes=$strokes aborts=$aborts missed=${tracker.missed} frames=$frames stale=$stale")
                stopped = true; onStop()
            }
            return
        }
        gameSeen = true
        otherSinceMs = -1

        lastGrey = f.planetGrey
        val seen = "${f.timeMs} lag=${nowMs - f.timeMs} grey=%.2f missed=${tracker.missed} ".format(f.planetGrey) + describe(tracks)
        if (nowMs < strokeBusyUntilMs) {
            val s = inFlight ?: return log("$seen | busy")
            // Re-check every frame: new bombs appear, and known ones reveal their real velocity.
            val why = blockReason(s, tracks.filter { it.kind == Kind.BOMB }, f.aspect, fromMs = nowMs)
            log("$seen | busy" + (why?.let { " ABORT $it" } ?: ""))
            if (why != null) abort(tracks, f.aspect, nowMs)
            return
        }
        val s = planner.plan(tracks, nowMs, f.aspect, masks)
        if (s == null) {
            val b = planner.blocked
            return log("$seen | none edge=${b[0]} mask=${b[1]} bomb=${b[2]}")
        }
        inFlight = s
        strokeBusyUntilMs = s.startMs + s.segments.sumOf { it.durationMs }
        strokes++
        log("$seen | STROKE ${s.targetIds} at=${s.startMs} " + s.segments.joinToString(" ") { "%.2f,%.2f>%.2f,%.2f".format(it.a.x, it.a.y, it.b.x, it.b.y) })
        dispatch(s)
    }

    /** e.g. "m3#12(.50,1.02 v.0013) b7(.31,.88 r.08)": kind, id, seen-once marker, position, fall speed or radius. */
    private fun describe(tracks: List<Track>) = tracks.joinToString(" ") { t ->
        val k = if (t.kind == Kind.METEOR) "m" else "b"
        val once = if (t.seen == 1) "?" else ""
        val extra = if (t.kind == Kind.METEOR) "v%.4f".format(t.vel.y) else "r%.3f".format(t.radius)
        "$k${t.id}$once(%.2f,%.2f %s)".format(t.pos.x, t.pos.y, extra)
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
        aborts++
        dispatch(tap)
    }
}
