package iris.tools.airlift

/** Copy policy comes exclusively from the caller; Airlift owns transport, task list and telemetry. */
object RsyncCommand {
    fun build(config: AirliftConfig, transport: String, source: String, destination: String, recursive: Boolean = false): List<String> = buildList {
        add(config.rsyncExecutable)
        addAll(config.rsyncOptions?.let(RsyncArguments::parse).orEmpty())
        addAll(listOf("--relative", "--protect-args", "--from0", "--files-from=-", "--info=progress2", "--outbuf=L", "--stats"))
        // Overrides a user -h: progress and --stats must print plain byte counts to be parsed.
        add("--no-human-readable")
        add("--info=name1")
        add("--out-format=AIRLIFT_FILE:%n")
        if (recursive) add("--recursive")
        addAll(listOf("-e", transport, "--", source, destination))
    }
}
