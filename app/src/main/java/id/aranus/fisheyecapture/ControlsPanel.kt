package id.aranus.fisheyecapture

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * Panel bawah berisi slider kontrol UVC. Live view tetap terlihat di atasnya,
 * jadi efek perubahan langsung kelihatan. Hanya kontrol yang didukung kamera
 * yang ditampilkan.
 */
class ControlsPanel(private val activity: Activity, private val store: ControlStore) {

    /** Kontrol dikirim dari thread sendiri: thread kamera sibuk di loop live view. */
    private val io = Executors.newSingleThreadExecutor()
    private var dialog: Dialog? = null

    fun show() {
        io.execute {
            val state = CameraControls.read()
            activity.runOnUiThread { if (!activity.isFinishing) render(state) }
        }
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    private fun render(state: CameraControls) {
        dismiss()
        val dp = activity.resources.displayMetrics.density
        val pad = (16 * dp).toInt()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor("#161B22"))
        }

        content.addView(TextView(activity).apply {
            text = "Atur Gambar"
            setTextColor(Color.WHITE)
            textSize = 17f
        })

        if (state.supported.isEmpty() && !state.autoExposureSupported && !state.autoWhiteBalanceSupported) {
            content.addView(note("Kamera ini tidak menyediakan kontrol gambar UVC."))
        }

        val manualForAe = mutableListOf<SeekBar>()
        val manualForAwb = mutableListOf<SeekBar>()

        if (state.autoExposureSupported) {
            content.addView(switch("Auto exposure", state.autoExposure) { on ->
                io.execute { UvcNative.setAutoExposure(on) }
                store.saveAutoExposure(on)
                manualForAe.forEach { it.isEnabled = !on }
            })
        }
        if (state.autoWhiteBalanceSupported) {
            content.addView(switch("Auto white balance", state.autoWhiteBalance) { on ->
                io.execute { UvcNative.setAutoWhiteBalance(on) }
                store.saveAutoWhiteBalance(on)
                manualForAwb.forEach { it.isEnabled = !on }
            })
        }

        for (s in state.supported) {
            val label = TextView(activity).apply {
                setTextColor(Color.parseColor("#C9D1D9"))
                textSize = 13f
                setPadding(0, (12 * dp).toInt(), 0, 0)
            }
            fun showValue(v: Int) {
                label.text = "${s.control.label}: $v  (default ${s.default})"
            }
            showValue(s.current)
            val bar = SeekBar(activity).apply {
                min = s.min
                max = s.max
                progress = s.current
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        showValue(v)
                        io.execute { UvcNative.setControl(s.control.ordinal, v) }
                    }

                    override fun onStartTrackingTouch(sb: SeekBar) = Unit
                    override fun onStopTrackingTouch(sb: SeekBar) = store.saveValue(s.control, sb.progress)
                })
            }
            // Exposure/gain hanya bisa diatur manual saat auto exposure mati; WB juga.
            when (s.control) {
                ImageControl.EXPOSURE, ImageControl.GAIN -> {
                    manualForAe += bar
                    bar.isEnabled = !(state.autoExposureSupported && state.autoExposure)
                }
                ImageControl.WHITE_BALANCE -> {
                    manualForAwb += bar
                    bar.isEnabled = !(state.autoWhiteBalanceSupported && state.autoWhiteBalance)
                }
                else -> Unit
            }
            content.addView(label)
            content.addView(bar)
        }

        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (16 * dp).toInt(), 0, 0)
        }
        buttons.addView(Button(activity).apply {
            text = "Reset default"
            setOnClickListener {
                store.clear()
                io.execute {
                    if (state.autoExposureSupported) UvcNative.setAutoExposure(true)
                    if (state.autoWhiteBalanceSupported) UvcNative.setAutoWhiteBalance(true)
                    for (c in state.supported) UvcNative.setControl(c.control.ordinal, c.default)
                }
                show() // baca ulang dari kamera
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(Button(activity).apply {
            text = "Tutup"
            setOnClickListener { dismiss() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(buttons)

        val d = Dialog(activity)
        d.setContentView(ScrollView(activity).apply { addView(content) })
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (activity.resources.displayMetrics.heightPixels * 0.5).toInt())
            setDimAmount(0f)
            addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        }
        d.show()
        dialog = d
    }

    private fun note(text: String) = TextView(activity).apply {
        this.text = text
        setTextColor(Color.parseColor("#9AA4B2"))
        textSize = 13f
    }

    private fun switch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) = Switch(activity).apply {
        text = label
        setTextColor(Color.WHITE)
        isChecked = checked
        setPadding(0, (8 * activity.resources.displayMetrics.density).toInt(), 0, 0)
        setOnCheckedChangeListener { _, on -> onChange(on) }
    }
}
