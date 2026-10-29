package iris.tools.airlift

import kotlin.test.*

class RsyncOutputParserTest {
    @Test fun `collect final counters without pretending progress is network traffic`() {
        val parser = RsyncOutputParser()
        val running = parser.parse(" 1,000  50%  1.00MB/s  0:00:01")!!
        assertNull(running.matchedBytes)
        assertNull(running.literalBytes)
        assertEquals(1234, parser.parse("Literal data: 1,234 bytes")!!.literalBytes)
        val complete = parser.parse("Matched data: 766 bytes")!!
        assertEquals(766, complete.matchedBytes)
        assertEquals(1234, complete.literalBytes)
        assertEquals(1000, complete.logicalBytes)
        assertNull(RsyncOutputParser().parse("permission denied"))
        assertNull(RsyncOutputParser().parse("Literal data: nonsense bytes"))
    }

    @Test fun `non-ASCII names escaped by rsync in the C locale match the submitted list`() {
        // Captured from rsync 3.2.7 with LC_ALL=C for "файл.bin".
        val parser = RsyncOutputParser(listOf("plain.bin", "dir/файл.bin"))
        val progress = parser.parse("AIRLIFT_FILE:dir/\\#321\\#204\\#320\\#260\\#320\\#271\\#320\\#273.bin")!!
        assertEquals("dir/файл.bin", progress.currentFile)
        assertEquals(2, progress.currentFileIndex)
        assertEquals(1, parser.parse("AIRLIFT_FILE:plain.bin")!!.currentFileIndex)
        assertNull(parser.parse("AIRLIFT_FILE:unknown.bin"))
    }

    @Test fun `escape decoding keeps literal text emoji and an escaped backslash`() {
        assertEquals("a\\#12b", RsyncText.decode("a\\#134#12b"))
        assertEquals("tab\there", RsyncText.decode("tab\\#011here"))
        assertEquals("😀 ok", RsyncText.decode("😀 ok"))
        assertEquals("\\#9", RsyncText.decode("\\#9"))
    }

    @Test fun `plain byte counts printed with --no-human-readable are parsed`() {
        val parser = RsyncOutputParser()
        assertEquals(5_000_000, parser.parse("        5000000 100%  175.45MB/s    0:00:00 (xfr#2, to-chk=0/2)")!!.logicalBytes)
        assertEquals(5_000_000, parser.parse("Literal data: 5000000 bytes")!!.literalBytes)
    }
}
