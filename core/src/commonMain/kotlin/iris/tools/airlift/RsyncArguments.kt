package iris.tools.airlift

/** Splits a quoted argument string, without evaluating shell expressions or expanding variables. */
object ArgumentText {
    fun split(text: String, name: String): List<String> {
        require('\u0000' !in text) { "NUL is not allowed in $name arguments" }
        val result = mutableListOf<String>()
        val token = StringBuilder()
        var quote: Char? = null
        var present = false
        var i = 0
        while (i < text.length) {
            val ch = text[i++]
            when {
                quote == '\'' -> if (ch == '\'') quote = null else token.append(ch)
                ch == '\\' && quote != '\'' -> {
                    require(i < text.length) { "Trailing escape in $name" }
                    val next = text[i]
                    if (quote == '"' && next !in charArrayOf('"', '\\', '$', '`', '\n')) token.append(ch)
                    else { token.append(next); i++ }
                    present = true
                }
                quote == '"' -> if (ch == '"') quote = null else token.append(ch)
                ch == '\'' || ch == '"' -> { quote = ch; present = true }
                ch.isWhitespace() -> if (present) { result.add(token.toString()); token.setLength(0); present = false }
                else -> { token.append(ch); present = true }
            }
        }
        require(quote == null) { "Unclosed quote in $name" }
        if (present) result.add(token.toString())
        return result
    }
}

/** Copy options supplied by the user. Airlift owns the transport, the stdin file list and the operands. */
object RsyncArguments {
    private const val OWNED = "Airlift manages -e/--rsh, --files-from, standard input and source/destination; " +
        "configure SSH with --port, --identity and --ssh"
    // Short rsync options that take a value: the rest of the cluster or the next argument.
    private const val SHORT_WITH_VALUE = "BeTfM@"
    // Long options whose value may follow as a separate argument; that value is never parsed as options.
    private val longWithValue = setOf("--exclude", "--include", "--suffix", "--partial-dir", "--temp-dir",
        "--compare-dest", "--copy-dest", "--link-dest", "--backup-dir", "--bwlimit", "--max-size", "--min-size",
        "--max-delete", "--chmod", "--chown", "--usermap", "--groupmap", "--log-file", "--log-file-format",
        "--out-format", "--password-file", "--timeout", "--contimeout", "--iconv", "--info", "--debug",
        "--skip-compress", "--modify-window", "--block-size", "--checksum-choice", "--cc", "--compress-choice",
        "--zc", "--compress-level", "--zl", "--protocol", "--port", "--sockopts", "--outbuf", "--remote-option",
        "--write-batch", "--read-batch", "--only-write-batch", "--checksum-seed", "--rsync-path", "--address",
        "--stop-after", "--stop-at", "--early-input", "--max-alloc", "--copy-as", "--time-limit", "--stderr")
    private val stdinFilter = Regex("""^(?:merge|dir-merge|\.|:)[^ _]*[ _]+-$""")

    fun parse(text: String): List<String> {
        val result = ArgumentText.split(text, "--rsync")
        var i = 0
        while (i < result.size) {
            val token = result[i++]
            fun value(inline: String?): String? = inline ?: result.getOrNull(i)?.also { i++ }
            when {
                token == "--" -> throw IllegalArgumentException(OWNED)
                token.startsWith("--") -> {
                    val name = token.substringBefore('=')
                    val inline = if ('=' in token) token.substringAfter('=') else null
                    require(name != "--rsh" && name != "--files-from") { OWNED }
                    when (name) {
                        // These would consume the NUL file list that Airlift writes to rsync's stdin.
                        "--exclude-from", "--include-from" -> require(value(inline) != "-") { OWNED }
                        "--filter" -> require(value(inline)?.let { stdinFilter.matches(it.trim()) } != true) { OWNED }
                        in longWithValue -> value(inline)
                    }
                }
                token.startsWith("-") && token.length > 1 -> {
                    for (index in 1 until token.length) {
                        val option = token[index]
                        require(option != 'e') { OWNED }
                        if (option in SHORT_WITH_VALUE) {
                            val inline = token.substring(index + 1).ifEmpty { null }
                            val argument = value(inline)
                            if (option == 'f') require(argument?.let { stdinFilter.matches(it.trim()) } != true) { OWNED }
                            break
                        }
                    }
                }
                // An operand here would become an extra rsync source; values must belong to an option.
                else -> throw IllegalArgumentException("Unexpected argument '$token' in --rsync. $OWNED; write option values as --option=value")
            }
        }
        return result
    }
}

/** Additional OpenSSH client options. Options that would break worker ControlMasters are rejected. */
object SshOptions {
    private const val WITH_VALUE = "BbcDEeFIiJLlmOoPpQRSWw"
    // Port and identity have their own Airlift options; the rest control multiplexing, forwarding or exit early.
    private const val FORBIDDEN = "DfGLMNnOpQRSsTtVWwi"
    private val controlKeys = setOf("controlmaster", "controlpath", "controlpersist", "remotecommand", "requesttty",
        "sessiontype", "stdinnull", "forkafterauthentication", "localforward", "remoteforward", "dynamicforward", "tunnel")

    fun parse(text: String): List<String> {
        val result = ArgumentText.split(text, "--ssh")
        var i = 0
        while (i < result.size) {
            val token = result[i++]
            require(token.length > 1 && token.startsWith('-') && !token.startsWith("--")) {
                "Unsupported SSH option: $token. Use short OpenSSH options, for example -o Compression=no or -c aes128-gcm@openssh.com"
            }
            for (index in 1 until token.length) {
                val option = token[index]
                require(option !in FORBIDDEN) {
                    "SSH option -$option is managed by Airlift or would break worker connections; use --port and --identity for port and key"
                }
                if (option in WITH_VALUE) {
                    val value = token.substring(index + 1).ifEmpty { requireNotNull(result.getOrNull(i++)) { "Missing value for SSH option -$option" } }
                    if (option == 'o') require(value.substringBefore('=').substringBefore(' ').trim().lowercase() !in controlKeys) {
                        "SSH option $value is managed by Airlift"
                    }
                    break
                }
            }
        }
        return result
    }
}
