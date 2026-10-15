package id.aranus.fisheyecapture

import android.content.Context
import kotlin.math.abs
import org.json.JSONObject

/**
 * Satu entri camera-parameters.json (salinan dari backend Merchant Hub,
 * assets/camera-parameters.json). Foto harus frame penuh dengan rasio yang
 * sama dengan kalibrasi; kalau rasio beda, kamera meng-crop sensor dan
 * pusat/jari-jari lensa tidak cocok lagi.
 */
data class CameraCalibration(
    val name: String,
    val imageWidth: Int,
    val imageHeight: Int,
    val icx: Double,
    val icy: Double,
    val fov: Int,
) {
    private val ratio = imageWidth.toDouble() / imageHeight

    fun sameRatio(m: CaptureMode): Boolean {
        if (m.width <= 0 || m.height <= 0) return false
        val ar = m.width.toDouble() / m.height
        return abs(ar - ratio) / ratio < 0.02 // ~2% toleransi pembulatan, sama dengan MoilMeet
    }

    /**
     * Mode foto, mengikuti chooseOptimalMode() MoilMeet (camera_caps.cpp), tapi
     * untuk foto diam FPS tidak penting: rasio sama + lebar <= kalibrasi →
     * resolusi terbesar. Tanpa mode serasio, ambil yang terbesar (diberi
     * peringatan di UI karena geometrinya tidak akan cocok).
     */
    fun chooseCaptureMode(modes: List<CaptureMode>): CaptureMode? {
        val best = compareBy<CaptureMode>({ it.area }, { it.fps })
        return modes.filter { sameRatio(it) && it.width <= imageWidth }.maxWithOrNull(best)
            ?: modes.maxWithOrNull(best)
    }

    /**
     * Mode live view + rekaman: port langsung chooseOptimalMode() MoilMeet
     * (camera_caps.cpp), jadi resolusinya sama dengan yang dipilih MoilMeet.
     *  1) rasio sama + lebar <= kalibrasi + fps >= target → area terbesar
     *  2) rasio sama + lebar <= kalibrasi → fps tertinggi
     *  3) tidak ada mode serasio → mode terbesar (diberi peringatan)
     */
    fun chooseVideoMode(modes: List<CaptureMode>): CaptureMode? {
        val ok = modes.filter { sameRatio(it) && it.width <= imageWidth }
        return ok.filter { it.fps + 0.5 >= TARGET_FPS }.maxByOrNull { it.area }
            ?: ok.maxWithOrNull(compareBy({ it.fps }, { it.area }))
            ?: modes.maxWithOrNull(compareBy({ it.area }, { it.fps }))
    }

    /** Skala foto terhadap kalibrasi. */
    fun scaleOf(m: CaptureMode): Double = m.width.toDouble() / imageWidth

    val label: String get() = "$name · ${imageWidth}x$imageHeight · ${if (fov > 0) "$fov°" else "FOV ?"}"

    companion object {
        /** Sama dengan target FPS default MoilMeet (main.cpp: target_fps = 20). */
        const val TARGET_FPS = 20
        const val DEFAULT_NAME = "lrcp_imx586_240_17"

        /** Semua parameter dari assets/camera-parameters.json, urut nama. */
        fun loadAll(context: Context): List<CameraCalibration> {
            val json = context.assets.open("camera-parameters.json").bufferedReader().use { it.readText() }
            val root = JSONObject(json)
            return root.keys().asSequence().mapNotNull { key ->
                val e = root.optJSONObject(key) ?: return@mapNotNull null
                val w = e.optInt("imageWidth")
                val h = e.optInt("imageHeight")
                if (w <= 0 || h <= 0) return@mapNotNull null
                CameraCalibration(key, w, h, e.optDouble("iCx"), e.optDouble("iCy"), e.optInt("cameraFov"))
            }.sortedBy { it.name.lowercase() }.toList()
        }
    }
}
