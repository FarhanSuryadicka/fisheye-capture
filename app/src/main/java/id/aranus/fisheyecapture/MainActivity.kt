package id.aranus.fisheyecapture

import android.app.Activity
import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val ACTION_USB_PERMISSION = "id.aranus.fisheyecapture.USB_PERMISSION"
private const val ALBUM = "FisheyeCapture"
private const val PREFS = "fisheye"
private const val PREF_PARAM = "parameter"
private const val REQ_CAMERA = 1

/** Frame awal dibuang supaya auto-exposure kamera sempat menyesuaikan. */
private const val WARMUP_FRAMES = 4
private const val CAPTURE_DEADLINE_MS = 6000L
private const val PREVIEW_INTERVAL_MS = 100L

/**
 * Buka app → kamera USB langsung jalan → live view fisheye utuh (fitCenter,
 * tidak ada crop) → Foto (resolusi 4:3 terbesar) atau Rekam (mode live yang
 * sama dengan MoilMeet). File disimpan apa adanya dari kamera.
 */
class MainActivity : Activity() {

    private lateinit var usbManager: UsbManager
    private lateinit var status: TextView
    private lateinit var info: TextView
    private lateinit var preview: ImageView
    private lateinit var photoButton: Button
    private lateinit var recordButton: Button
    private lateinit var paramButton: Button
    private lateinit var adjustButton: Button
    private var panel: ControlsPanel? = null
    /** Status auto exposure / white balance kamera, untuk ditampilkan di info. */
    private var autoText = ""
    private lateinit var startButton: Button

    private lateinit var calibrations: List<CameraCalibration>
    private lateinit var calibration: CameraCalibration
    /** Kamera hanya dibuka setelah parameter dipilih dan Start ditekan. */
    private var started = false
    private var permissionPending = false
    private val main = Handler(Looper.getMainLooper())

    /** Semua panggilan native lewat satu thread ini (bridge tidak re-entrant). */
    private val camera = Executors.newSingleThreadExecutor()
    private val live = AtomicBoolean(false)
    private val recording = AtomicBoolean(false)

    // Koneksi harus disimpan: kalau ter-GC, Android menutup fd yang dipakai libusb.
    private var connection: UsbDeviceConnection? = null
    private var photoMode: CaptureMode? = null
    private var videoMode: CaptureMode? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> onPermissionResult(intent)
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> if (started) connectCamera()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    close()
                    setStatus("Kamera dilepas. Colokkan lagi kamera fisheye USB…")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        usbManager = getSystemService(USB_SERVICE) as UsbManager
        status = findViewById(R.id.status)
        info = findViewById(R.id.info)
        preview = findViewById(R.id.preview)
        photoButton = findViewById(R.id.photo)
        recordButton = findViewById(R.id.record)
        paramButton = findViewById(R.id.parameter)
        adjustButton = findViewById(R.id.adjust)
        adjustButton.setOnClickListener { panel?.show() }
        startButton = findViewById(R.id.start)
        setupParameterPicker()
        startButton.setOnClickListener { if (started) stopCamera() else startCamera() }
        photoButton.setOnClickListener { takePhoto() }
        recordButton.setOnClickListener { if (recording.get()) stopRecording() else startRecording() }

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }
        setStatus("Pilih parameter kamera, colokkan kamera, lalu tekan Start.")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (started && connection == null) connectCamera()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(usbReceiver) }
        close()
        camera.shutdown()
        super.onDestroy()
    }

    // ---------------- Parameter kamera ----------------

    private fun setupParameterPicker() {
        calibrations = CameraCalibration.loadAll(this)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val saved = prefs.getString(PREF_PARAM, CameraCalibration.DEFAULT_NAME)
        val index = calibrations.indexOfFirst { it.name == saved }
            .takeIf { it >= 0 } ?: calibrations.indexOfFirst { it.name == CameraCalibration.DEFAULT_NAME }.coerceAtLeast(0)
        calibration = calibrations[index]
        showParameter()
        paramButton.setOnClickListener {
            val current = calibrations.indexOf(calibration)
            // Dialog pilihan tunggal: item aktif bertanda radio dan list langsung
            // ter-scroll ke sana; tinggi dialog dibatasi sistem (tidak menutupi app).
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Pilih parameter kamera")
                .setSingleChoiceItems(calibrations.map { it.label }.toTypedArray(), current) { d, which ->
                    calibration = calibrations[which]
                    prefs.edit().putString(PREF_PARAM, calibration.name).apply()
                    showParameter()
                    d.dismiss()
                }
                .setNegativeButton("Batal", null)
                .show()
        }
    }

    private fun showParameter() {
        paramButton.text = "Parameter: ${calibration.label}  ▾"
    }

    private fun startCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
            return
        }
        started = true
        paramButton.isEnabled = false
        startButton.text = "Stop"
        connectCamera()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_CAMERA) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            setStatus("Izin Kamera diperlukan untuk kamera USB. Aktifkan di Setelan ▸ Aplikasi ▸ Fisheye Capture ▸ Izin.")
        }
    }

    private fun stopCamera() {
        started = false
        permissionPending = false
        close()
        paramButton.isEnabled = true
        startButton.text = "Start"
        info.text = ""
        preview.setImageDrawable(null)
        setStatus("Kamera berhenti. Pilih parameter lalu tekan Start.")
    }

    // ---------------- USB ----------------

    private fun findCamera(): UsbDevice? = usbManager.deviceList.values.firstOrNull { d ->
        (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_VIDEO }
    }

    private fun connectCamera() {
        val device = findCamera() ?: run {
            setStatus("Colokkan kamera fisheye USB (OTG)…")
            return
        }
        if (usbManager.hasPermission(device)) {
            open(device)
        } else if (!permissionPending) {
            permissionPending = true
            // MUTABLE: sistem menambahkan EXTRA_DEVICE + EXTRA_PERMISSION_GRANTED ke intent.
            // Dengan IMMUTABLE extra itu hilang dan di sebagian HP (mis. MIUI) hasPermission()
            // masih false tepat saat broadcast datang → izin terlihat "ditolak".
            // Intent eksplisit (setPackage) wajib untuk PendingIntent mutable di Android 12+.
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(this, 0, intent, flags or PendingIntent.FLAG_UPDATE_CURRENT)
            usbManager.requestPermission(device, pi)
            setStatus("Izinkan akses kamera USB…")
        }
    }

    private fun onPermissionResult(intent: Intent) {
        permissionPending = false
        if (!started) return
        val device = intent.usbDevice() ?: findCamera() ?: return
        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
        when {
            granted || usbManager.hasPermission(device) -> open(device)
            // Beberapa ROM mengirim broadcast sebelum izin tercatat: cek sekali lagi.
            else -> main.postDelayed({
                if (started && usbManager.hasPermission(device)) open(device)
                else setStatus("Izin USB ditolak. Tekan Stop lalu Start untuk meminta lagi.")
            }, 700)
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.usbDevice(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else getParcelableExtra(UsbManager.EXTRA_DEVICE)

    private fun open(device: UsbDevice) {
        if (connection != null) return
        val conn = usbManager.openDevice(device) ?: run {
            setStatus("Gagal membuka kamera USB")
            return
        }
        connection = conn
        setStatus("Membuka kamera…")
        camera.execute {
            if (!UvcNative.openDevice(conn.fileDescriptor)) {
                runOnUiThread { setStatus("Kamera bukan UVC / gagal dibuka") }
                return@execute
            }
            val modes = UvcNative.getSupportedModes().toModes()
            photoMode = calibration.chooseCaptureMode(modes)
            videoMode = calibration.chooseVideoMode(modes)
            // Terapkan pengaturan gambar tersimpan untuk parameter ini, lalu baca status auto.
            val store = ControlStore(getSharedPreferences(PREFS, MODE_PRIVATE), calibration.name)
            store.applyTo(CameraControls.read())
            val controls = CameraControls.read()
            autoText = listOfNotNull(
                if (controls.autoExposureSupported) "Auto exposure: ${if (controls.autoExposure) "ON" else "OFF"}" else null,
                if (controls.autoWhiteBalanceSupported) "Auto WB: ${if (controls.autoWhiteBalance) "ON" else "OFF"}" else null,
            ).joinToString(" · ")
            panel = ControlsPanel(this, store)
            runOnUiThread {
                if (videoMode == null) {
                    setStatus("Kamera tidak punya mode MJPEG")
                    return@runOnUiThread
                }
                showModeInfo()
                startLive()
            }
        }
    }

    private fun close() {
        panel?.dismiss()
        panel = null
        live.set(false)
        recording.set(false)
        setButtons(false)
        camera.execute { UvcNative.closeDevice() }
        // Tutup koneksi setelah handle native dilepas (antrian executor yang sama).
        val conn = connection
        connection = null
        camera.execute { conn?.close() }
    }

    // ---------------- Live view + rekaman ----------------

    /**
     * Satu loop untuk live view dan rekaman: stream di mode video, tampilkan
     * frame ~10 fps, dan selama [recording] tulis setiap frame asli ke AVI.
     */
    private fun startLive() {
        val mode = videoMode ?: return
        if (!live.compareAndSet(false, true)) return
        setButtons(true)
        setStatus("Live ${mode.width}x${mode.height}. Siap ambil foto / rekam.")
        camera.execute {
            if (UvcNative.startStream(mode.width, mode.height, mode.fps).isEmpty()) {
                live.set(false)
                runOnUiThread { setStatus("Kamera menolak mode $mode") }
                return@execute
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleFor(mode.width, 1080) }
            var recorder: Recording? = null
            var lastPreview = 0L
            try {
                while (live.get()) {
                    if (recording.get() && recorder == null) {
                        recorder = runCatching { newRecording(mode) }
                            .onFailure { e -> recording.set(false); runOnUiThread { setStatus("Gagal rekam: ${e.message}") } }
                            .getOrNull()
                    }
                    if (!recording.get() && recorder != null) {
                        val r = recorder
                        recorder = null
                        finishRecording(r)
                    }
                    val jpeg = UvcNative.grabFrame(500) ?: continue
                    recorder?.let { r ->
                        r.writer.write(jpeg)
                        if (r.writer.bytesWritten > AviMjpegWriter.MAX_BYTES) recording.set(false)
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastPreview >= PREVIEW_INTERVAL_MS) {
                        lastPreview = now
                        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts) ?: continue
                        val rec = recorder
                        runOnUiThread {
                            if (live.get()) preview.setImageBitmap(bmp)
                            if (rec != null) setStatus("● REC ${rec.elapsedText()} · ${rec.writer.frames} frame · ${rec.writer.bytesWritten / 1_048_576} MB")
                        }
                    }
                }
            } finally {
                recorder?.let { finishRecording(it) }
                UvcNative.stopStream()
            }
        }
    }

    private fun startRecording() {
        if (!live.get()) return
        recording.set(true)
        photoButton.isEnabled = false
        recordButton.text = "Stop Rekam"
    }

    private fun stopRecording() {
        recording.set(false)
    }

    /** Jalan di thread kamera. */
    private fun finishRecording(r: Recording) {
        val result = runCatching { r.finish(contentResolver) }
        runOnUiThread {
            recordButton.text = "Rekam"
            photoButton.isEnabled = live.get()
            result.onSuccess { setStatus("Video tersimpan: Movies/$ALBUM/${r.name} (${r.writer.frames} frame)") }
                .onFailure { setStatus("Gagal menyimpan video: ${it.message}") }
        }
    }

    private inner class Recording(val name: String, val uri: Uri, val writer: AviMjpegWriter) {
        private val startedAt = SystemClock.elapsedRealtime()

        fun elapsedText(): String {
            val s = (SystemClock.elapsedRealtime() - startedAt) / 1000
            return "%02d:%02d".format(s / 60, s % 60)
        }

        fun finish(resolver: android.content.ContentResolver) {
            val secs = (SystemClock.elapsedRealtime() - startedAt) / 1000.0
            writer.finish(if (secs > 0) writer.frames / secs else 0.0)
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        }
    }

    /** Buat file AVI di Movies/FisheyeCapture (ditulis langsung lewat MediaStore). */
    private fun newRecording(mode: CaptureMode): Recording {
        val name = fileName("avi", mode.width, mode.height)
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/x-msvideo")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/$ALBUM")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: error("galeri tidak bisa ditulis")
        val pfd = contentResolver.openFileDescriptor(uri, "rw") ?: error("galeri tidak bisa ditulis")
        return Recording(name, uri, AviMjpegWriter(FileOutputStream(pfd.fileDescriptor), mode.width, mode.height, mode.fps, pfd))
    }

    // ---------------- Foto ----------------

    private fun takePhoto() {
        val mode = photoMode ?: return
        setButtons(false)
        setStatus("Mengambil foto ${mode.width}x${mode.height}…")
        live.set(false) // loop live berhenti + stopStream() sebelum tugas di bawah jalan
        camera.execute {
            val result = runCatching { grabStill(mode) }
            runOnUiThread {
                result.onSuccess { (name, size) -> setStatus("Foto tersimpan: Pictures/$ALBUM/$name ($size)") }
                    .onFailure { setStatus("Gagal: ${it.message}") }
                if (connection != null) startLive()
            }
        }
    }

    /** Jalan di thread kamera. Mengembalikan (nama file, ukuran). */
    private fun grabStill(mode: CaptureMode): Pair<String, String> {
        val active = UvcNative.startStream(mode.width, mode.height, mode.fps)
        if (active.isEmpty()) error("kamera menolak mode ${mode.width}x${mode.height}")
        var jpeg: ByteArray? = null
        try {
            val deadline = SystemClock.elapsedRealtime() + CAPTURE_DEADLINE_MS
            var valid = 0
            while (SystemClock.elapsedRealtime() < deadline && valid <= WARMUP_FRAMES) {
                // Simpan frame valid terakhir: di USB isochronous Android, frame resolusi
                // tinggi kadang rusak/nol setelah beberapa frame — yang valid tetap dipakai.
                val f = UvcNative.grabFrame(1500) ?: continue
                jpeg = f
                valid++
            }
        } finally {
            UvcNative.stopStream()
        }
        val bytes = jpeg ?: error("tidak ada frame valid dari kamera")

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) error("frame JPEG rusak")

        val name = fileName("jpg", bounds.outWidth, bounds.outHeight)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$ALBUM")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: error("galeri tidak bisa ditulis")
        try {
            // Byte JPEG apa adanya dari kamera, tanpa kompres ulang.
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("galeri tidak bisa ditulis")
            contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            throw e
        }
        return name to "${bounds.outWidth}x${bounds.outHeight}, ${bytes.size / 1024} KB"
    }

    // ---------------- UI ----------------

    private fun showModeInfo() {
        val p = photoMode ?: return
        val v = videoMode ?: return
        val c = calibration
        val s = c.scaleOf(p)
        val lines = mutableListOf(
            "Parameter: ${c.label}",
            "Foto: ${p.width}x${p.height} · Live/Rekam: $v",
            "Skala foto x%.3f · pusat lensa ≈ %.0f, %.0f".format(s, c.icx * s, c.icy * s),
        )
        if (!c.sameRatio(p)) {
            lines += "⚠ Kamera tidak punya mode serasio ${c.imageWidth}x${c.imageHeight} — parameter mungkin salah, geometri foto tidak akan cocok."
        }
        if (autoText.isNotEmpty()) lines += autoText
        info.text = lines.joinToString("\n")
    }

    private fun setButtons(enabled: Boolean) {
        photoButton.isEnabled = enabled
        recordButton.isEnabled = enabled
        adjustButton.isEnabled = enabled
        if (!enabled) recordButton.text = "Rekam"
    }

    private fun setStatus(text: String) {
        status.text = text
    }

    private fun fileName(ext: String, w: Int, h: Int): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "fisheye_${calibration.name}_${w}x${h}_$stamp.$ext"
    }

    private fun sampleFor(width: Int, target: Int): Int {
        var s = 1
        while (width / (s * 2) >= target) s *= 2
        return s
    }
}
