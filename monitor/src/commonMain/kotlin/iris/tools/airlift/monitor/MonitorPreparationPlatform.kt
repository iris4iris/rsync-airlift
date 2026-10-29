package iris.tools.airlift.monitor

import iris.tools.airlift.AirliftConfig
import kotlinx.io.files.Path

internal expect fun hasInteractiveConsole(): Boolean
internal expect fun readConsolePassword(prompt: String): CharArray?
internal expect fun confirmationColorsEnabled(): Boolean
internal expect fun askTransferConfirmation(text: String, swing: Boolean): Boolean

internal expect fun preparationProcesses(): iris.tools.airlift.process.ProcessLauncher
internal expect fun preparationDispatcher(): kotlinx.coroutines.CoroutineDispatcher
/** Lists [source] without following links below it. A trailing '/' (or "." / "..") follows a symlinked root directory;
 * maxDepth limits descent, reporting directories at that depth without their contents. */
internal expect suspend fun readLocalInventory(source: String, maxDepth: Int? = null): FileInventory
/** True when the path itself exists; a dangling symlink counts. */
internal expect fun localEntryExists(path: String): Boolean
/** Windows uses '\' as a separator; elsewhere it is a file-name character (and a mask escape). */
internal expect val windowsLocalPaths: Boolean
