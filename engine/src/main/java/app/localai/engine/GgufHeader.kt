package app.localai.engine

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Metadata read straight from a GGUF header; nothing here is guessed. Null = not present in the file. */
data class GgufSummary(
    val version: Int,
    val tensorCount: Long,
    val architecture: String?,
    val name: String?,
    val fileType: Int?,
    val contextLength: Long?,
    val blockCount: Long?,
    val embeddingLength: Long?,
    val headCount: Long?,
    val headCountKv: Long?,
    val hasChatTemplate: Boolean,
) {
    val quantization: String? get() = fileType?.let { QUANT_LABELS[it] ?: "unknown ($it)" }

    companion object {
        private val QUANT_LABELS = mapOf(
            0 to "F32", 1 to "F16", 2 to "Q4_0", 3 to "Q4_1", 7 to "Q8_0", 8 to "Q5_0", 9 to "Q5_1",
            10 to "Q2_K", 11 to "Q3_K_S", 12 to "Q3_K_M", 13 to "Q3_K_L", 14 to "Q4_K_S", 15 to "Q4_K_M",
            16 to "Q5_K_S", 17 to "Q5_K_M", 18 to "Q6_K", 19 to "IQ2_XXS", 20 to "IQ2_XS", 21 to "Q2_K_S",
            22 to "IQ3_XS", 23 to "IQ3_XXS", 24 to "IQ1_S", 25 to "IQ4_NL", 26 to "IQ3_S", 27 to "IQ3_M",
            28 to "IQ2_S", 29 to "IQ2_M", 30 to "IQ4_XS", 31 to "IQ1_M", 32 to "BF16", 36 to "TQ1_0",
            37 to "TQ2_0", 38 to "MXFP4_MOE", 39 to "NVFP4", 40 to "Q1_0",
        )
    }
}

class NotGgufException(message: String) : IOException(message)

/**
 * Streaming GGUF header parser. Reads only the metadata section sequentially
 * (large arrays such as the vocabulary are skipped, never held in memory) so
 * multi-gigabyte files are validated without loading them.
 */
object GgufHeader {
    private const val MAX_KEY_BYTES = 4096L
    private const val MAX_VALUE_STRING_BYTES = 1L shl 20
    private const val MAX_KV_COUNT = 1_000_000L

    fun read(input: InputStream): GgufSummary {
        val s = LeStream(DataInputStream(BufferedInputStream(input, 64 * 1024)))
        val magic = ByteArray(4)
        try { s.readFully(magic) } catch (e: EOFException) { throw NotGgufException("File is too small to be a GGUF model") }
        if (String(magic, Charsets.US_ASCII) != "GGUF") throw NotGgufException("Missing GGUF magic: this is not a GGUF file")
        val version = s.u32().toInt()
        if (version !in 1..3) throw NotGgufException("Unsupported GGUF version $version")
        val tensorCount = if (version == 1) s.u32() else s.u64()
        val kvCount = if (version == 1) s.u32() else s.u64()
        if (kvCount < 0 || kvCount > MAX_KV_COUNT) throw NotGgufException("Implausible metadata count: header is corrupt")

        var arch: String? = null
        var name: String? = null
        var fileType: Int? = null
        var hasTemplate = false
        val numbers = HashMap<String, Long>()
        val wanted = setOf("context_length", "block_count", "embedding_length", "attention.head_count", "attention.head_count_kv")

        try {
            for (i in 0 until kvCount) {
                val key = s.string(MAX_KEY_BYTES, version) ?: throw NotGgufException("Corrupt metadata key")
                val type = s.u32().toInt()
                when {
                    key == "general.architecture" && type == T_STRING -> arch = s.string(MAX_VALUE_STRING_BYTES, version)
                    key == "general.name" && type == T_STRING -> name = s.string(MAX_VALUE_STRING_BYTES, version)
                    key == "general.file_type" && isInt(type) -> fileType = s.intValue(type).toInt()
                    key == "tokenizer.chat_template" -> { hasTemplate = true; s.skipValue(type, version) }
                    isInt(type) && wanted.any { key.endsWith(".$it") } && key.substringBefore('.') != "tokenizer" ->
                        numbers[key] = s.intValue(type)
                    else -> s.skipValue(type, version)
                }
            }
        } catch (e: EOFException) {
            throw NotGgufException("File ends inside the metadata section: it is truncated or corrupt")
        }

        fun num(suffix: String): Long? = arch?.let { numbers["$it.$suffix"] }
        return GgufSummary(
            version = version,
            tensorCount = tensorCount,
            architecture = arch,
            name = name,
            fileType = fileType,
            contextLength = num("context_length"),
            blockCount = num("block_count"),
            embeddingLength = num("embedding_length"),
            headCount = num("attention.head_count"),
            headCountKv = num("attention.head_count_kv"),
            hasChatTemplate = hasTemplate,
        )
    }

    private const val T_UINT8 = 0; private const val T_INT8 = 1; private const val T_UINT16 = 2
    private const val T_INT16 = 3; private const val T_UINT32 = 4; private const val T_INT32 = 5
    private const val T_FLOAT32 = 6; private const val T_BOOL = 7; private const val T_STRING = 8
    private const val T_ARRAY = 9; private const val T_UINT64 = 10; private const val T_INT64 = 11
    private const val T_FLOAT64 = 12

    private fun isInt(t: Int) = t in setOf(T_UINT8, T_INT8, T_UINT16, T_INT16, T_UINT32, T_INT32, T_UINT64, T_INT64)

    private class LeStream(private val d: DataInputStream) {
        private val b8 = ByteArray(8)
        fun readFully(b: ByteArray) = d.readFully(b)
        private fun fill(n: Int): ByteBuffer { d.readFully(b8, 0, n); return ByteBuffer.wrap(b8, 0, n).order(ByteOrder.LITTLE_ENDIAN) }
        fun u8(): Long = (d.readUnsignedByte()).toLong()
        fun u32(): Long = fill(4).int.toLong() and 0xFFFFFFFFL
        fun u64(): Long = fill(8).long

        fun intValue(type: Int): Long = when (type) {
            T_UINT8 -> u8()
            T_INT8 -> d.readByte().toLong()
            T_UINT16 -> fill(2).short.toLong() and 0xFFFF
            T_INT16 -> fill(2).short.toLong()
            T_UINT32 -> u32()
            T_INT32 -> fill(4).int.toLong()
            T_UINT64, T_INT64 -> u64()
            else -> throw NotGgufException("Not an integer type: $type")
        }

        fun string(maxBytes: Long, version: Int): String? {
            val len = if (version == 1) u32() else u64()
            if (len < 0 || len > maxBytes) { skipFully(len); return null }
            val bytes = ByteArray(len.toInt())
            d.readFully(bytes)
            return String(bytes, Charsets.UTF_8)
        }

        fun skipFully(n: Long) {
            if (n < 0) throw NotGgufException("Negative length: header is corrupt")
            var left = n
            while (left > 0) {
                val skipped = d.skip(left)
                if (skipped <= 0) { d.readUnsignedByte(); left-- } else left -= skipped
            }
        }

        fun skipValue(type: Int, version: Int) {
            when (type) {
                T_UINT8, T_INT8, T_BOOL -> skipFully(1)
                T_UINT16, T_INT16 -> skipFully(2)
                T_UINT32, T_INT32, T_FLOAT32 -> skipFully(4)
                T_UINT64, T_INT64, T_FLOAT64 -> skipFully(8)
                T_STRING -> skipFully(if (version == 1) u32() else u64())
                T_ARRAY -> {
                    val elem = u32().toInt()
                    val n = if (version == 1) u32() else u64()
                    if (n < 0) throw NotGgufException("Negative array length: header is corrupt")
                    val fixed = when (elem) {
                        T_UINT8, T_INT8, T_BOOL -> 1L
                        T_UINT16, T_INT16 -> 2L
                        T_UINT32, T_INT32, T_FLOAT32 -> 4L
                        T_UINT64, T_INT64, T_FLOAT64 -> 8L
                        else -> 0L
                    }
                    if (fixed > 0) skipFully(n * fixed)
                    else for (i in 0 until n) skipValue(elem, version)
                }
                else -> throw NotGgufException("Unknown metadata value type $type")
            }
        }
    }
}
