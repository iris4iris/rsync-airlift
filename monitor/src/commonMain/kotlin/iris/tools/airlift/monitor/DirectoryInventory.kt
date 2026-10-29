package iris.tools.airlift.monitor

/** Initial listing below scope. incomplete: directories with entries that were skipped, so they never collapse. */
internal data class DirectoryInventory(val sourceRoot: String?, val scope: String, val files: Set<String>,
    val directories: Set<String>, val otherEntries: Set<String>, val incomplete: Set<String> = emptySet())
