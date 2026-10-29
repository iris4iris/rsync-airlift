package iris.tools.airlift.monitor

import kotlinx.io.Buffer
import kotlinx.io.readByteArray

private const val CHUNK = 65536

internal class MonitorOutput(private val socket: MonitorSocket) {
    private val buffer = Buffer()
    fun writeBoolean(value: Boolean) { buffer.writeByte(if (value) 1 else 0) }
    fun writeInt(value: Int) { buffer.writeInt(value) }
    fun writeLong(value: Long) { buffer.writeLong(value) }
    fun writeDouble(value: Double) { buffer.writeLong(value.toBits()) }
    fun write(bytes: ByteArray) { buffer.write(bytes) }
    /** Protocol handshakes use Java's modified UTF-8 for short strings. */
    fun writeUTF(text: String) {
        val encoded = modifiedUtf8(text)
        require(encoded.size <= 65535)
        buffer.writeShort(encoded.size.toShort())
        buffer.write(encoded)
    }
    /** Streams complete chunks so large responses never accumulate in memory before the first byte. */
    suspend fun drain() {
        while (buffer.size >= CHUNK) socket.write(buffer.readByteArray(CHUNK))
    }
    suspend fun flush() {
        while (buffer.size > 0) socket.write(buffer.readByteArray(minOf(buffer.size, CHUNK.toLong()).toInt()))
        socket.flush()
    }
}

internal fun modifiedUtf8(text: String): ByteArray {
    val encoded = Buffer()
    for (char in text) {
        val code = char.code
        when {
            code in 1..127 -> encoded.writeByte(code.toByte())
            code <= 2047 -> { encoded.writeByte((192 or (code shr 6)).toByte()); encoded.writeByte((128 or (code and 63)).toByte()) }
            else -> { encoded.writeByte((224 or (code shr 12)).toByte()); encoded.writeByte((128 or ((code shr 6) and 63)).toByte()); encoded.writeByte((128 or (code and 63)).toByte()) }
        }
    }
    return encoded.readByteArray()
}

internal class MonitorInput(private val socket: MonitorSocket) {
    private val bytes = ByteArray(CHUNK)
    private var position = 0
    private var size = 0
    private suspend fun fill() {
        size = socket.read(bytes); position = 0
        check(size > 0) { "Unexpected end of monitor connection" }
    }
    private suspend fun byte(): Int {
        if (position == size) fill()
        return bytes[position++].toInt() and 255
    }
    suspend fun readBoolean(): Boolean = byte() != 0
    suspend fun readInt(): Int = (byte() shl 24) or (byte() shl 16) or (byte() shl 8) or byte()
    suspend fun readLong(): Long = (readInt().toLong() shl 32) or (readInt().toLong() and 0xffffffffL)
    suspend fun readDouble(): Double = Double.fromBits(readLong())
    /** Copies whole buffered chunks rather than single bytes. */
    suspend fun readFully(destination: ByteArray) {
        var offset = 0
        while (offset < destination.size) {
            if (position == size) fill()
            val count = minOf(size - position, destination.size - offset)
            bytes.copyInto(destination, offset, position, position + count)
            position += count; offset += count
        }
    }
    /** Raw modified UTF-8 bytes; a declared length above [maxBytes] is rejected before the body is read. */
    suspend fun readUTFBytes(maxBytes: Int = 65535): ByteArray {
        val count = (byte() shl 8) or byte()
        require(count <= maxBytes) { "Monitor handshake field is too long" }
        return ByteArray(count).also { readFully(it) }
    }
    suspend fun readUTF(maxBytes: Int = 65535): String = decodeModifiedUtf8(readUTFBytes(maxBytes))
}

internal fun decodeModifiedUtf8(data: ByteArray): String {
    var index = 0
    fun next(): Int { require(index < data.size) { "Malformed modified UTF-8" }; return data[index++].toInt() and 255 }
    fun continuation(): Int = next().also { require(it and 192 == 128) { "Malformed modified UTF-8" } } and 63
    return buildString {
        while (index < data.size) {
            val first = next()
            val code = when {
                first < 128 -> first
                first and 224 == 192 -> ((first and 31) shl 6) or continuation()
                first and 240 == 224 -> ((first and 15) shl 12) or (continuation() shl 6) or continuation()
                else -> error("Malformed modified UTF-8")
            }
            append(code.toChar())
        }
    }
}

/** Running time depends only on [expected]'s length, never on where the first mismatch occurs. */
internal fun constantTimeEquals(expected: ByteArray, actual: ByteArray): Boolean {
    var difference = expected.size xor actual.size
    for (index in expected.indices) {
        val other = if (index < actual.size) actual[index].toInt() else 0
        difference = difference or (expected[index].toInt() xor other)
    }
    return difference == 0
}
