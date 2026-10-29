package iris.tools.airlift.monitor

import iris.tools.airlift.AirliftSnapshot
import kotlinx.coroutines.CancellationException

internal const val CONNECTION_LOST = "Connection lost, retrying..."

class AirliftMonitor(private val source: MonitorSource, private val title: String = source.title, private val swing: Boolean = false) {
    private val renderer = MonitorRenderer(source, title)
    /** Monotonic clock in nanoseconds; replaceable so tests can drive refresh and retry timing. */
    internal var clock: () -> Long = { monitorMonotonicNanos() }
    fun frame(size: MonitorSize, snapshot: AirliftSnapshot, model: MonitorModel): MonitorFrame = renderer.render(size, snapshot, model)

    /**
     * One tick: render once, wait for input up to the refresh interval, apply every pending event to that
     * tick's snapshot, and fetch a new snapshot only when the interval elapsed or a command changed the source.
     * Q detaches only; it never closes the source or dispatcher.
     */
    suspend fun run(terminal: MonitorTerminal = createMonitorTerminal(title, swing)) {
        try {
            terminal.start()
            val model = MonitorModel()
            // The very first failure still ends an attach: there is nothing to show yet.
            var snapshot = source.snapshot()
            var fetchedAt = clock()
            var failingSince: Long? = null
            while (true) {
                model.expireMessage(clock())
                terminal.present(frame(terminal.size(), snapshot, model))
                // While the source is unreachable, retry less often but keep the panel responsive.
                val interval = if (failingSince == null) REFRESH_MILLIS else RETRY_MILLIS
                val wait = (interval - (clock() - fetchedAt) / 1_000_000).coerceIn(0, interval)
                var event = terminal.awaitEvent(wait)
                var due = event == null
                var handled = 0
                while (event != null) {
                    when (apply(event, model, snapshot)) {
                        Outcome.EXIT -> return
                        Outcome.CHANGED -> due = true
                        Outcome.LOCAL -> Unit
                    }
                    if (++handled >= MAX_EVENTS_PER_TICK) break
                    event = terminal.pollEvent()
                }
                val now = clock()
                // Input alone only re-renders the current snapshot.
                if (!due && now - fetchedAt < interval * 1_000_000) continue
                fetchedAt = now
                try {
                    snapshot = source.snapshot()
                    failingSince = null
                    model.connectionStatus = null
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    // Keep the last frame; give up only after a sustained outage.
                    val since = failingSince ?: now.also { failingSince = it }
                    if (clock() - since >= GIVE_UP_MILLIS * 1_000_000) throw error
                    model.connectionStatus = CONNECTION_LOST
                }
            }
        } finally { terminal.close() }
    }

    private enum class Outcome { EXIT, LOCAL, CHANGED }

    private suspend fun apply(event: MonitorEvent, model: MonitorModel, snapshot: AirliftSnapshot): Outcome {
        if (event is MonitorEvent.Mouse) {
            model.handleMouse(event, snapshot)
            return Outcome.LOCAL
        }
        val key = event as? MonitorEvent.Key ?: return Outcome.LOCAL
        // Saturating moves: End followed by Down must not overflow before the next frame clamps the selection.
        fun move(delta: Int) { model.selected = (model.selected.toLong() + delta).coerceIn(0, Int.MAX_VALUE.toLong()).toInt() }
        when (key.key) {
            MonitorKey.EOF -> return Outcome.EXIT
            MonitorKey.ArrowUp -> move(-1)
            MonitorKey.ArrowDown -> move(1)
            MonitorKey.ArrowLeft -> if (model.view == MonitorView.WORKERS) move(-model.gridRows)
            MonitorKey.ArrowRight -> if (model.view == MonitorView.WORKERS) move(model.gridRows)
            MonitorKey.PageUp -> move(-model.pageCapacity)
            MonitorKey.PageDown -> move(model.pageCapacity)
            MonitorKey.Home -> model.selected = 0
            MonitorKey.End -> model.selected = Int.MAX_VALUE
            MonitorKey.F2, MonitorKey.Enter -> model.details = !model.details
            MonitorKey.Tab -> { model.view = MonitorView.entries[(model.view.ordinal + 1) % 3]; model.selected = 0 }
            MonitorKey.Character -> {
                val char = key.character ?: ' '
                if (char.lowercaseChar() == 'q') return Outcome.EXIT
                try {
                    model.handle(char, source)
                    model.message = ""
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { model.message = error.message ?: "Command failed" }
                // Dispatcher commands should be visible immediately rather than on the next refresh.
                return if (char.lowercaseChar() in COMMANDS) Outcome.CHANGED else Outcome.LOCAL
            }
            else -> Unit
        }
        return Outcome.LOCAL
    }

    private companion object {
        const val REFRESH_MILLIS = 200L
        const val RETRY_MILLIS = 1_000L
        const val GIVE_UP_MILLIS = 10_000L
        /** Bounds one tick so a flood of input can never starve rendering. */
        const val MAX_EVENTS_PER_TICK = 256
        const val COMMANDS = "p+=-"
    }
}
