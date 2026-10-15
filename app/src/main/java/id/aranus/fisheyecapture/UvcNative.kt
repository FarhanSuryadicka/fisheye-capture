package id.aranus.fisheyecapture

/** JNI ke fisheye_capture_jni.c. Semua pemanggilan dilakukan dari satu thread. */
object UvcNative {
    init {
        System.loadLibrary("fisheyecapture")
    }

    external fun openDevice(fd: Int): Boolean
    external fun closeDevice()

    /** Mode MJPEG kamera sebagai triple (width, height, fps). */
    external fun getSupportedModes(): IntArray

    /** Mulai stream; mengembalikan {w, h, fps} hasil negosiasi atau array kosong. */
    external fun startStream(w: Int, h: Int, fps: Int): IntArray
    external fun stopStream()

    /** Byte MJPEG asli satu frame, atau null bila timeout / frame rusak. */
    external fun grabFrame(timeoutMs: Int): ByteArray?
    external fun getLogText(): String
}

data class CaptureMode(val width: Int, val height: Int, val fps: Int) {
    val area: Long get() = width.toLong() * height
    override fun toString() = "${width}x$height@${fps}fps"
}

fun IntArray.toModes(): List<CaptureMode> =
    (0 until size / 3).map { CaptureMode(this[it * 3], this[it * 3 + 1], this[it * 3 + 2]) }
