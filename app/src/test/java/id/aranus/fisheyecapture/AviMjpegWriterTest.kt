package id.aranus.fisheyecapture

import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class AviMjpegWriterTest {
    /** Tulis 5 frame dari JPEG uji (env FISHEYE_TEST_JPEG) ke build/test.avi. */
    @Test
    fun writesReadableAvi() {
        val src = System.getenv("FISHEYE_TEST_JPEG") ?: return
        val jpeg = File(src).readBytes()
        val out = File("build/test.avi")
        val w = AviMjpegWriter(FileOutputStream(out), 2912, 2176, 20)
        repeat(5) { w.write(jpeg) }
        w.finish(10.0)
        val b = out.readBytes()
        assertEquals("RIFF", String(b, 0, 4))
        assertEquals(b.size - 8L, (b[4].toLong() and 0xff) or ((b[5].toLong() and 0xff) shl 8) or
            ((b[6].toLong() and 0xff) shl 16) or ((b[7].toLong() and 0xff) shl 24))
    }
}
