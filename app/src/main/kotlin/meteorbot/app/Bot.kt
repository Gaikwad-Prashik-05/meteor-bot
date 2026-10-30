package meteorbot.app

import meteorbot.core.RunController

// shortcut: process-global refs between the two services, fine for a single-process app
object Bot {
    @Volatile var swipe: SwipeService? = null
    @Volatile var capture: CaptureService? = null
    @Volatile var controller: RunController? = null
}
