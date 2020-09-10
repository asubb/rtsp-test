package rtsp.test

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** RIFF constant which should always be first 4 bytes of the wav-file. */
const val RIFF = 0x52494646
/** WAVE constant. */
const val WAVE = 0x57415645
/** "fmt " constant */
const val FMT = 0x666D7420
/** PCM format constant */
const val PCM_FORMAT = 0x0100.toShort()
/** "data" constant */
const val DATA = 0x64617461

enum class BitDepth(val bits: Int, val bytesPerSample: Int) {
    BIT_8(8, 1),
    BIT_16(16, 2),
    BIT_24(24, 3),
    BIT_32(32, 4),
    BIT_64(64, 8);

    companion object {
        fun of(bits: Int): BitDepth = safelyOf(bits) ?: throw UnsupportedOperationException("$bits is unsupported bit depth")
        fun safelyOf(bits: Int): BitDepth? = values().firstOrNull { it.bits == bits }
    }
}

class WavHeader(
        private val bitDepth: BitDepth,
        private val sampleRate: Float,
        private val numberOfChannels: Int,
        private val dataSize: Int
) {
    fun header(): ByteArray {
        val destination = ByteArrayOutputStream()

        /** Create sub chunk 1 content*/
        val sc1Content = ByteArrayOutputStream()
        val sc1ContentStream = DataOutputStream(sc1Content)
        writeConstantShort("PCM audio format", sc1ContentStream, PCM_FORMAT)
        writeLittleEndianIntAsShort("numberOfChannels", sc1ContentStream, numberOfChannels)
        writeLittleEndianInt("sampleRate", sc1ContentStream, sampleRate.toInt())
        writeLittleEndianInt("byteRate", sc1ContentStream, sampleRate.toInt() * numberOfChannels * bitDepth.bytesPerSample)
        writeLittleEndianIntAsShort("byteAlign", sc1ContentStream, numberOfChannels * bitDepth.bytesPerSample)
        writeLittleEndianIntAsShort("bitDepth", sc1ContentStream, bitDepth.bits)
        val sc1 = sc1Content.toByteArray()

        /** Creating sub chunk. */
        val subChunk1ByteArrayStream = ByteArrayOutputStream()
        val chunk1Stream = DataOutputStream(subChunk1ByteArrayStream)
        writeConstantInt("WAVE", chunk1Stream, WAVE)
        writeConstantInt("fmt", chunk1Stream, FMT)
        writeLittleEndianInt("subChunk1Size", chunk1Stream, sc1.size)
        chunk1Stream.write(sc1)
        val subChunk1 = subChunk1ByteArrayStream.toByteArray()

        val dos = DataOutputStream(destination)
        /** Chunk */
        writeConstantInt("RIFF", dos, RIFF)
        val chunkSize = 4 + (8 + subChunk1.size) + (8 + dataSize)
        writeLittleEndianInt("chunkSize", dos, chunkSize)
        /** Sub Chunk 1 */
        dos.write(subChunk1)

        /** Sub Chunk 2 */
        writeConstantInt("Data", dos, DATA)
        writeLittleEndianInt("dataSize", dos, dataSize)

        return destination.toByteArray()
    }

    private fun writeConstantInt(target: String, d: DataOutputStream, v: Int) {
        try {
            d.writeInt(v)
        } catch (e: Exception) {
            throw IllegalStateException("Can't write `$v` for `$target`.", e)
        }
    }

    private fun writeConstantShort(target: String, d: DataOutputStream, v: Short) {
        try {
            d.writeShort(v.toInt())
        } catch (e: Exception) {
            throw IllegalStateException("Can't write `$v` for `$target`.", e)
        }
    }

    private fun writeLittleEndianInt(target: String, d: DataOutputStream, v: Int) {
        try {
            val b = (0..3)
                    .map { ((v shr (it * 8)) and 0xFF).toByte() }
                    .toByteArray()
            d.write(b)
        } catch (e: Exception) {
            throw IllegalStateException("Can't write `$v` for $target.", e)
        }

    }

    private fun writeLittleEndianIntAsShort(target: String, d: DataOutputStream, v: Int) {
        try {
            val b = (0..1)
                    .map { ((v shr (it * 8)) and 0xFF).toByte() }
                    .toByteArray()
            d.write(b)
        } catch (e: Exception) {
            throw IllegalStateException("Can't write `$v` for $target", e)
        }

    }
}