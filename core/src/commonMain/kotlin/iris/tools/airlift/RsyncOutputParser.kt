package iris.tools.airlift

/** One parser per rsync attempt; --stats counters are final, not real-time network counters. */
class RsyncOutputParser(files: Collection<String> = emptyList()) {
    // 1-based positions in the submitted list, for "file N/M" in the monitor.
    private val positions = HashMap<String, Int>(files.size * 2).also { map ->
        files.forEachIndexed { index, file -> map.getOrPut(file) { index + 1 } }
    }
    private var latest = TransferProgress(0, null, null)
    private var matched: Long? = null
    private var literal: Long? = null

    fun parse(record: String): TransferProgress? {
        if (record.startsWith(FILE_PREFIX)) {
            val name = RsyncText.decode(record.removePrefix(FILE_PREFIX)).removePrefix("./")
            val position = positions[name]
            if (position != null) {
                latest = latest.copy(currentFile = name, currentFileIndex = position)
                return latest
            }
        }
        ProgressParser.parse(record)?.let {
            val transferred = transferredFiles.find(record)?.groupValues?.get(1)?.toIntOrNull()
            latest = it.copy(matchedBytes = matched, literalBytes = literal, currentFile = latest.currentFile,
                currentFileIndex = latest.currentFileIndex, transferredFiles = transferred ?: latest.transferredFiles)
            return latest
        }
        val match = statistic.matchEntire(record.trim()) ?: return null
        val bytes = match.groupValues[2].replace(",", "").toLongOrNull() ?: return null
        if (match.groupValues[1] == "Matched data") matched = bytes else literal = bytes
        latest = latest.copy(matchedBytes = matched, literalBytes = literal)
        return latest
    }

    private companion object {
        const val FILE_PREFIX = "AIRLIFT_FILE:"
        val statistic = Regex("""^(Literal data|Matched data):\s+([\d,]+) bytes\s*$""")
        val transferredFiles = Regex("xfr#(\\d+)")
    }
}

/** rsync's terminal-safe output: with LC_ALL=C it prints every non-ASCII or control byte as \#ooo. */
internal object RsyncText {
    fun decode(text: String): String {
        if ("\\#" !in text) return text
        val bytes = ArrayList<Byte>(text.length)
        var i = 0
        while (i < text.length) {
            if (text[i] == '\\' && i + 4 < text.length && text[i + 1] == '#' && (2..4).all { text[i + it] in '0'..'7' }) {
                bytes.add(text.substring(i + 2, i + 5).toInt(8).toByte())
                i += 5
            } else {
                // Copy a run of ordinary characters (possibly a surrogate pair) as UTF-8.
                val start = i
                i++
                while (i < text.length && text[i] != '\\') i++
                text.substring(start, i).encodeToByteArray().forEach { bytes.add(it) }
            }
        }
        return bytes.toByteArray().decodeToString()
    }
}
