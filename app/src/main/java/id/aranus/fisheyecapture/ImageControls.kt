package id.aranus.fisheyecapture

import android.content.SharedPreferences

/** Urutan harus sama dengan enum C_* di fisheye_capture_jni.c. */
enum class ImageControl(val label: String) {
    BRIGHTNESS("Brightness"),
    CONTRAST("Contrast"),
    SATURATION("Saturation"),
    SHARPNESS("Sharpness"),
    GAMMA("Gamma"),
    HUE("Hue"),
    GAIN("Gain"),
    WHITE_BALANCE("White balance (K)"),
    EXPOSURE("Exposure"),
    BACKLIGHT("Backlight compensation"),
    POWER_LINE("Anti-flicker (0 off · 1 50Hz · 2 60Hz)"),
}

data class ControlState(
    val control: ImageControl,
    val supported: Boolean,
    val min: Int,
    val max: Int,
    val default: Int,
    val current: Int,
)

data class CameraControls(
    val controls: List<ControlState>,
    val autoExposureSupported: Boolean,
    val autoExposure: Boolean,
    val autoWhiteBalanceSupported: Boolean,
    val autoWhiteBalance: Boolean,
) {
    val supported: List<ControlState> get() = controls.filter { it.supported && it.max > it.min }

    companion object {
        fun read(): CameraControls {
            val v = UvcNative.getControls()
            val n = ImageControl.entries.size
            if (v.size < n * 5 + 4) return CameraControls(emptyList(), false, false, false, false)
            val list = ImageControl.entries.mapIndexed { i, c ->
                ControlState(c, v[i * 5] == 1, v[i * 5 + 1], v[i * 5 + 2], v[i * 5 + 3], v[i * 5 + 4])
            }
            return CameraControls(list, v[n * 5] == 1, v[n * 5 + 1] == 1, v[n * 5 + 2] == 1, v[n * 5 + 3] == 1)
        }
    }
}

/**
 * Pengaturan disimpan per parameter kamera dan diterapkan lagi setiap kamera
 * dibuka, karena kamera UVC kembali ke default firmware saat dicabut.
 */
class ControlStore(private val prefs: SharedPreferences, private val camera: String) {
    private fun key(name: String) = "ctl.$camera.$name"

    fun saveValue(c: ImageControl, value: Int) = prefs.edit().putInt(key(c.name), value).apply()
    fun saveAutoExposure(on: Boolean) = prefs.edit().putBoolean(key("AE"), on).apply()
    fun saveAutoWhiteBalance(on: Boolean) = prefs.edit().putBoolean(key("AWB"), on).apply()

    fun clear() {
        val e = prefs.edit()
        prefs.all.keys.filter { it.startsWith("ctl.$camera.") }.forEach { e.remove(it) }
        e.apply()
    }

    /** Terapkan nilai tersimpan ke kamera (dipanggil setelah kamera dibuka). */
    fun applyTo(state: CameraControls) {
        if (prefs.contains(key("AE")) && state.autoExposureSupported) {
            UvcNative.setAutoExposure(prefs.getBoolean(key("AE"), true))
        }
        if (prefs.contains(key("AWB")) && state.autoWhiteBalanceSupported) {
            UvcNative.setAutoWhiteBalance(prefs.getBoolean(key("AWB"), true))
        }
        for (s in state.supported) {
            if (prefs.contains(key(s.control.name))) {
                UvcNative.setControl(s.control.ordinal, prefs.getInt(key(s.control.name), s.default))
            }
        }
    }
}
