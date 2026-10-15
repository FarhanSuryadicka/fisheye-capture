package id.aranus.fisheyecapture

import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Menulis frame MJPEG kamera apa adanya ke file .avi (tanpa encode ulang),
 * jadi resolusi dan isi frame sama persis dengan output kamera, termasuk
 * resolusi di atas batas encoder H.264 HP (biasanya 4096 px).
 * Bisa diputar di VLC / Windows; tiap frame bisa diekstrak jadi JPEG utuh.
 *
 * AVI 1.0 memakai ukuran 32-bit, jadi file dibatasi [MAX_BYTES].
 */
class AviMjpegWriter(
    private val out: FileOutputStream,
    private val width: Int,
    private val height: Int,
    nominalFps: Int,
    /** Ditutup bersama [out]; null bila stream dibuka dari path biasa. */
    private val owner: java.io.Closeable? = null,
) {
    companion object {
        const val MAX_BYTES = 3_800_000_000L
        private const val HEADER_SIZE = 224 // RIFF..movi, lihat writeHeader()
    }

    private val ch: FileChannel = out.channel
    private val index = ArrayList<IntArray>() // (offset dari 'movi', ukuran)
    private var moviBytes = 4L // fourcc 'movi'
    private var maxFrame = 0
    private val fps = nominalFps.coerceAtLeast(1)

    val frames: Int get() = index.size
    val bytesWritten: Long get() = HEADER_SIZE + moviBytes

    init {
        ch.position(0)
        writeHeader(fps.toDouble())
    }

    fun write(jpeg: ByteArray) {
        val pad = jpeg.size and 1
        val chunk = le(8).put("00dc".toByteArray()).putInt(jpeg.size)
        index += intArrayOf(moviBytes.toInt(), jpeg.size)
        writeFully(chunk.flip() as ByteBuffer)
        writeFully(ByteBuffer.wrap(jpeg))
        if (pad == 1) writeFully(ByteBuffer.wrap(byteArrayOf(0)))
        moviBytes += 8L + jpeg.size + pad
        if (jpeg.size > maxFrame) maxFrame = jpeg.size
    }

    /** Tulis indeks, lalu perbarui header dengan FPS nyata hasil pengukuran. */
    fun finish(measuredFps: Double) {
        ch.position(HEADER_SIZE + moviBytes)
        val idx = le(8 + index.size * 16).put("idx1".toByteArray()).putInt(index.size * 16)
        for ((off, size) in index) {
            idx.put("00dc".toByteArray()).putInt(0x10).putInt(off).putInt(size) // AVIIF_KEYFRAME
        }
        writeFully(idx.flip() as ByteBuffer)
        val fileSize = ch.position()
        ch.position(0)
        writeHeader(if (measuredFps > 0.5) measuredFps else fps.toDouble(), fileSize)
        out.fd.sync()
        out.close()
        owner?.close()
    }

    private fun writeHeader(rate: Double, fileSize: Long = 0) {
        val usPerFrame = (1_000_000.0 / rate).toInt()
        val scale = 1000
        val rateScaled = (rate * scale).toInt()
        val b = le(HEADER_SIZE)
        b.put("RIFF".toByteArray()).putInt((if (fileSize > 0) fileSize - 8 else 0).toInt())
        b.put("AVI ".toByteArray())
        // hdrl
        b.put("LIST".toByteArray()).putInt(192).put("hdrl".toByteArray())
        b.put("avih".toByteArray()).putInt(56)
        b.putInt(usPerFrame).putInt(0).putInt(0).putInt(0x10) // AVIF_HASINDEX
        b.putInt(index.size).putInt(0).putInt(1).putInt(maxFrame)
        b.putInt(width).putInt(height).putInt(0).putInt(0).putInt(0).putInt(0)
        // strl
        b.put("LIST".toByteArray()).putInt(116).put("strl".toByteArray())
        b.put("strh".toByteArray()).putInt(56)
        b.put("vids".toByteArray()).put("MJPG".toByteArray())
        b.putInt(0).putShort(0).putShort(0).putInt(0)
        b.putInt(scale).putInt(rateScaled).putInt(0).putInt(index.size)
        b.putInt(maxFrame).putInt(-1).putInt(0)
        b.putShort(0).putShort(0).putShort(width.toShort()).putShort(height.toShort())
        b.put("strf".toByteArray()).putInt(40) // BITMAPINFOHEADER
        b.putInt(40).putInt(width).putInt(height).putShort(1).putShort(24)
        b.put("MJPG".toByteArray()).putInt(width * height * 3)
        b.putInt(0).putInt(0).putInt(0).putInt(0)
        // movi
        b.put("LIST".toByteArray()).putInt((moviBytes).toInt()).put("movi".toByteArray())
        check(b.position() == HEADER_SIZE) { "AVI header size ${b.position()}" }
        writeFully(b.flip() as ByteBuffer)
    }

    private fun writeFully(buf: ByteBuffer) {
        while (buf.hasRemaining()) ch.write(buf)
    }

    private fun le(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
}
