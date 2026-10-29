package iris.tools.airlift.monitor

import iris.tools.airlift.AirliftConfig
import iris.tools.airlift.SshAuthentication

internal data class SshCheckResult(val exitCode: Int, val output: String)

/** Trust and password prompts happen before discovery, worker threads and the TUI. */
internal suspend fun checkSshConnection(
    config: AirliftConfig,
    interactive: Boolean = hasInteractiveConsole(),
    authenticate: (List<String>) -> List<String> = { SshAuthentication.command(it) },
    passwordAvailable: () -> Boolean = { SshAuthentication.hasPassword() },
    readPassword: (String) -> CharArray? = ::readConsolePassword,
    installPassword: (CharArray) -> Unit = { SshAuthentication.usePassword(it) },
    execute: suspend (List<String>, Boolean) -> SshCheckResult = { command, prompt -> runSshCheck(command, prompt) },
) {
    val base = buildList {
        add(config.sshExecutable)
        addAll(listOf("-p", config.port.toString(), "-o", "ConnectTimeout=${config.connectTimeoutSeconds}",
            "-o", "ServerAliveInterval=30", "-o", "ServerAliveCountMax=3"))
        config.identityFile?.let { addAll(listOf("-i", it.absolutePath().toString())) }
    }
    // OpenSSH keeps the first value of an option: Airlift's options come first, then the user's --ssh options.
    fun strict() = authenticate(base + listOf("-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes") + config.sshArguments +
        listOf(config.host, "true"))
    val first = execute(strict(), false)
    if (first.exitCode == 0) return
    var failure = first
    if (unknownHostKey(first.output)) {
        check(interactive) {
            "SSH host key is unknown for ${config.host}. Run Airlift in an interactive terminal to review and accept the key, or connect with ssh first. ${first.output}"
        }
        println("SSH host key is unknown for ${config.host}. Review the fingerprint below; enter yes to save it, or no to cancel.")
        // OpenSSH displays the fingerprint and writes to its configured known_hosts.
        val prompt = base + listOf("-o", "BatchMode=no", "-o", "StrictHostKeyChecking=ask",
            "-o", "PreferredAuthentications=none", "-o", "NumberOfPasswordPrompts=0") + config.sshArguments + listOf(config.host, "true")
        execute(prompt, true)
        // Authentication failure in the trust-only invocation is expected.
        failure = execute(strict(), false)
        if (failure.exitCode == 0) return
    }
    val passwordRejected = Regex("Permission denied", RegexOption.IGNORE_CASE).containsMatchIn(failure.output)
    if (passwordRejected) {
        check(interactive) {
            if (passwordAvailable()) "SSH password authentication failed. Run in an interactive terminal to enter a replacement password, or correct SSHPASS. ${failure.output}"
            else "SSH key authentication failed. Configure a key/ssh-agent or export SSHPASS for a headless run. ${failure.output}"
        }
        val password = readPassword("${config.host}'s SSH password: ") ?: error("SSH password input cancelled or unavailable")
        try { installPassword(password) } finally { password.fill('\u0000') }
        val retry = execute(strict(), false)
        check(retry.exitCode == 0) { "SSH password authentication failed: ${retry.output}" }
        return
    }
    error("SSH connection failed: ${failure.output}")
}

internal fun unknownHostKey(output: String): Boolean =
    "REMOTE HOST IDENTIFICATION HAS CHANGED" !in output &&
        "Offending " !in output &&
        Regex("No .* host key is known for .* and you have requested strict checking", RegexOption.IGNORE_CASE).containsMatchIn(output)

