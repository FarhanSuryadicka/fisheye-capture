package id.aranus.fisheyecapture

import android.graphics.BitmapFactory

/**
 * Menunggu auto-exposure kamera stabil setelah ganti mode stream.
 *
 * Saat pindah dari mode live ke mode foto (8000x6000), AE kamera mulai dari
 * awal dan beberapa frame pertama putih/terlalu terang. Di resolusi tinggi
 * fps-nya rendah, jadi membuang N frame tetap tidak cukup. Di sini kecerahan
 * rata-rata tiap frame diukur (decode sangat kecil, murah) dan frame dianggap
 * stabil setelah beberapa frame berturut-turut nyaris tidak berubah.
 */
class ExposureSettle(
    private val minFrames: Int = 3,
    private val stableRuns: Int = 2,
    private val tolerance: Double = 0.03,
) {
    private var count = 0
    private var last = -1.0
    private var runs = 0

    /** Masukkan frame berikutnya; true bila exposure sudah stabil di frame ini. */
    fun offer(jpeg: ByteArray): Boolean {
        val luma = meanLuma(jpeg) ?: return false
        count++
        if (last >= 0) {
            val change = kotlin.math.abs(luma - last) / maxOf(last, 1.0)
            runs = if (change <= tolerance) runs + 1 else 0
        }
        last = luma
        return count >= minFrames && runs >= stableRuns
    }

    companion object {
        /** Kecerahan rata-rata 0..255 dari versi ~250 px frame (null bila gagal decode). */
        fun meanLuma(jpeg: ByteArray): Double? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 250) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            bmp.recycle()
            var sum = 0L
            for (c in px) {
                sum += (299 * ((c shr 16) and 0xFF) + 587 * ((c shr 8) and 0xFF) + 114 * (c and 0xFF)) / 1000
            }
            return sum.toDouble() / px.size
        }
    }
}
