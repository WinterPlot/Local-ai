package app.localai.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GgufHeaderTest {
    private class W {
        val out = ByteArrayOutputStream()
        private fun le(n: Int, v: Long) { val b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array(); out.write(b, 0, n) }
        fun u32(v: Long) = le(4, v)
        fun u64(v: Long) = le(8, v)
        fun str(s: String) { val b = s.toByteArray(); u64(b.size.toLong()); out.write(b) }
        fun kvString(k: String, v: String) { str(k); u32(8); str(v) }
        fun kvU32(k: String, v: Long) { str(k); u32(4); u32(v) }
        fun kvStringArray(k: String, items: List<String>) { str(k); u32(9); u32(8); u64(items.size.toLong()); items.forEach { str(it) } }
        fun bytes() = out.toByteArray()
    }

    private fun header(kv: W.() -> Unit, count: Long): ByteArray {
        val body = W().apply(kv)
        val w = W()
        w.out.write("GGUF".toByteArray()); w.u32(3); w.u64(7); w.u64(count)
        w.out.write(body.bytes())
        return w.bytes()
    }

    @Test fun readsKnownFieldsAndSkipsArrays() {
        val data = header({
            kvString("general.architecture", "llama")
            kvString("general.name", "Tiny")
            kvU32("general.file_type", 15)
            kvStringArray("tokenizer.ggml.tokens", List(1000) { "tok$it" })
            kvString("tokenizer.chat_template", "{{ messages }}")
            kvU32("llama.context_length", 2048)
            kvU32("llama.block_count", 22)
            kvU32("llama.embedding_length", 2048)
            kvU32("llama.attention.head_count", 32)
            kvU32("llama.attention.head_count_kv", 4)
        }, 10)
        val s = GgufHeader.read(ByteArrayInputStream(data))
        assertEquals("llama", s.architecture)
        assertEquals("Tiny", s.name)
        assertEquals("Q4_K_M", s.quantization)
        assertEquals(2048L, s.contextLength)
        assertEquals(22L, s.blockCount)
        assertEquals(4L, s.headCountKv)
        assertEquals(7L, s.tensorCount)
        assertTrue(s.hasChatTemplate)
    }

    @Test fun missingFieldsStayNullInsteadOfBeingInvented() {
        val s = GgufHeader.read(ByteArrayInputStream(header({ kvString("general.architecture", "qwen2") }, 1)))
        assertEquals("qwen2", s.architecture)
        assertNull(s.contextLength)
        assertNull(s.quantization)
        assertFalse(s.hasChatTemplate)
    }

    @Test fun rejectsNonGguf() {
        try { GgufHeader.read(ByteArrayInputStream("not a model at all".toByteArray())); fail() } catch (_: NotGgufException) {}
    }

    @Test fun rejectsTruncatedMetadata() {
        val data = header({ kvString("general.architecture", "llama") }, 5) // claims 5 entries, has 1
        try { GgufHeader.read(ByteArrayInputStream(data)); fail() } catch (_: NotGgufException) {}
    }

    @Test fun rejectsEmptyFile() {
        try { GgufHeader.read(ByteArrayInputStream(ByteArray(0))); fail() } catch (_: NotGgufException) {}
    }
}
