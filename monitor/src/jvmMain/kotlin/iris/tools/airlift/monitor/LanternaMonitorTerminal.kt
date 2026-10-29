package iris.tools.airlift.monitor

import com.googlecode.lanterna.TextColor
import com.googlecode.lanterna.TerminalTextUtils
import com.googlecode.lanterna.input.KeyStroke
import com.googlecode.lanterna.input.MouseAction
import com.googlecode.lanterna.screen.Screen
import iris.tools.airlift.AirliftSnapshot

/** The only bridge between monitor frames/events and Lanterna. */
class LanternaMonitorTerminal(private val screen: Screen) : MonitorTerminal {
    private var firstFrame = true
    private var resized = false
    override fun start() { screen.startScreen(); screen.cursorPosition = null }
    override fun size(): MonitorSize {
        resized = screen.doResizeIfNecessary() != null || resized
        return MonitorSize(screen.terminalSize.columns, screen.terminalSize.rows)
    }
    override fun present(frame: MonitorFrame) {
        drawFrame(screen, frame)
        screen.refresh(if (firstFrame || resized) Screen.RefreshType.COMPLETE else Screen.RefreshType.DELTA)
        firstFrame = false
        resized = false
    }
    override fun pollEvent(): MonitorEvent? = screen.pollInput()?.toMonitorEvent()
    override fun close() = screen.close()
}

internal fun KeyStroke.toMonitorEvent(): MonitorEvent = if (this is MouseAction)
    MonitorEvent.Mouse(MonitorMouseAction.entries.firstOrNull { it.name == actionType.name } ?: MonitorMouseAction.OTHER,
        button, position.column, position.row)
else MonitorEvent.Key(MonitorKey.entries.firstOrNull { it.name == keyType.name } ?: MonitorKey.Other, character)

private fun MonitorColor.lanterna(): TextColor = when (this) {
    is MonitorColor.ANSI -> TextColor.ANSI.valueOf(name)
    is MonitorColor.RGB -> TextColor.RGB(red, green, blue)
}

internal fun drawFrame(screen: Screen, frame: MonitorFrame) {
    val graphics = screen.newTextGraphics()
    graphics.foregroundColor = TextColor.ANSI.WHITE
    graphics.backgroundColor = TextColor.ANSI.BLACK
    graphics.fill(' ')
    for (run in frame.runs) {
        graphics.foregroundColor = run.foreground.lanterna()
        graphics.backgroundColor = run.background.lanterna()
        val (text, used) = clipColumns(run.text, run.columns)
        val padding = if (run.pad) " ".repeat((run.columns - used).coerceAtLeast(0)) else ""
        graphics.putString(run.x, run.y, text + padding)
    }
}

/** Longest prefix fitting [columns] display cells, in one pass and never splitting a surrogate pair. */
internal fun clipColumns(text: String, columns: Int): Pair<String, Int> {
    var used = 0
    var end = 0
    while (end < text.length) {
        val next = if (text[end].isHighSurrogate() && end + 1 < text.length && text[end + 1].isLowSurrogate()) end + 2 else end + 1
        val width = TerminalTextUtils.getColumnWidth(text.substring(end, next))
        if (used + width > columns) break
        used += width
        end = next
    }
    return (if (end == text.length) text else text.substring(0, end)) to used
}

/** JVM compatibility helpers for existing virtual-terminal checks. */
fun AirliftMonitor.render(screen: Screen, snapshot: AirliftSnapshot, model: MonitorModel) =
    drawFrame(screen, frame(MonitorSize(screen.terminalSize.columns, screen.terminalSize.rows), snapshot, model))
internal fun MonitorModel.handleMouse(action: MouseAction, snapshot: AirliftSnapshot) =
    handleMouse(action.toMonitorEvent() as MonitorEvent.Mouse, snapshot)

internal actual fun createMonitorTerminal(title: String, swing: Boolean): MonitorTerminal =
    LanternaMonitorTerminal(TerminalAppearance.createScreen(title, swing))

suspend fun AirliftMonitor.run(screen: Screen) = run(LanternaMonitorTerminal(screen))
