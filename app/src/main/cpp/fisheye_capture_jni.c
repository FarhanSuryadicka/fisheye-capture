/* Minimal UVC bridge for FisheyeCapture: open the USB camera by fd, list its
 * MJPEG modes, stream one mode, and hand Kotlin the camera's own MJPEG frame
 * bytes (no decode / re-encode, so the saved photo is the original frame).
 *
 * Reuses the Android-patched libuvc, mode enumeration and DHT fix from
 * room-surveillance-project. See that project's CLAUDE.md for why libuvc's
 * transfer-buffer constants must not be raised on Android. */
#include <jni.h>
#include <libusb.h>
#include <libuvc/libuvc.h>

#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "log_ring.h"
#include "uvc_mjpeg_dht_fix.h"
#include "uvc_mode_negotiation.h"

#define JNI_FN(name) Java_id_aranus_fisheyecapture_UvcNative_##name

static uvc_context_t *g_ctx = NULL;
static uvc_device_handle_t *g_devh = NULL;
static uvc_stream_handle_t *g_strmh = NULL;
static int g_active[3] = {0, 0, 0};
static int g_libusb_opts_set = 0;
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;

static void stop_stream_locked(void) {
  if (g_strmh) {
    uvc_stream_stop(g_strmh);
    uvc_stream_close(g_strmh);
    g_strmh = NULL;
  }
  g_active[0] = g_active[1] = g_active[2] = 0;
}

JNIEXPORT jboolean JNICALL JNI_FN(openDevice)(JNIEnv *env, jobject thiz, jint fd) {
  (void)env;
  (void)thiz;
  jboolean ok = JNI_FALSE;
  pthread_mutex_lock(&g_lock);
  if (g_devh) {
    stop_stream_locked();
    uvc_close(g_devh);
    g_devh = NULL;
  }
  if (!g_libusb_opts_set) {
    /* Android: no device discovery, the fd comes from UsbManager. */
    libusb_set_option(NULL, LIBUSB_OPTION_NO_DEVICE_DISCOVERY, NULL);
    g_libusb_opts_set = 1;
  }
  if (!g_ctx && uvc_init(&g_ctx, NULL) < 0) {
    LOGE("uvc_init failed");
    g_ctx = NULL;
    goto done;
  }
  if (uvc_wrap((int)fd, g_ctx, &g_devh) < 0) {
    LOGE("uvc_wrap failed");
    g_devh = NULL;
    goto done;
  }
  LOGI("UVC device opened");
  ok = JNI_TRUE;
done:
  pthread_mutex_unlock(&g_lock);
  return ok;
}

JNIEXPORT void JNICALL JNI_FN(closeDevice)(JNIEnv *env, jobject thiz) {
  (void)env;
  (void)thiz;
  pthread_mutex_lock(&g_lock);
  stop_stream_locked();
  if (g_devh) {
    uvc_close(g_devh);
    g_devh = NULL;
  }
  pthread_mutex_unlock(&g_lock);
}

/* MJPEG modes as flattened (width, height, fps) triples, best-score first. */
JNIEXPORT jintArray JNICALL JNI_FN(getSupportedModes)(JNIEnv *env, jobject thiz) {
  (void)thiz;
  pthread_mutex_lock(&g_lock);
  int nc = 0;
  mode_candidate_t *cands = g_devh ? collect_modes(g_devh, &nc) : NULL;
  jint *tmp = nc > 0 ? (jint *)malloc(sizeof(jint) * (size_t)nc * 3) : NULL;
  if (tmp) {
    for (int i = 0; i < nc; i++) {
      tmp[i * 3 + 0] = cands[i].frame->wWidth;
      tmp[i * 3 + 1] = cands[i].frame->wHeight;
      tmp[i * 3 + 2] = cands[i].fps;
    }
  }
  free(cands);
  pthread_mutex_unlock(&g_lock);

  int n = tmp ? nc * 3 : 0;
  jintArray out = (*env)->NewIntArray(env, n);
  if (out && n) (*env)->SetIntArrayRegion(env, out, 0, n, tmp);
  free(tmp);
  return out;
}

/* Starts streaming the requested mode (falls back down the candidate list
 * if the camera rejects it). Returns the negotiated {w, h, fps}, or an empty
 * array on failure. */
JNIEXPORT jintArray JNICALL JNI_FN(startStream)(JNIEnv *env, jobject thiz, jint w, jint h,
                                                jint fps) {
  (void)thiz;
  int result[3] = {0, 0, 0};
  int got = 0;
  mode_candidate_t *cands = NULL;

  pthread_mutex_lock(&g_lock);
  if (!g_devh) {
    LOGE("startStream: no device open");
    goto done;
  }
  stop_stream_locked();

  int nc = 0;
  cands = collect_modes(g_devh, &nc);
  if (!nc) {
    LOGE("startStream: no MJPEG mode");
    goto done;
  }
  uvc_stream_ctrl_t ctrl;
  int chosen = -1;
  for (int i = find_mode_start_index(cands, nc, w, h, fps); i < nc; i++) {
    if (uvc_get_stream_ctrl_format_size(g_devh, &ctrl, cands[i].format,
                                        cands[i].frame->wWidth, cands[i].frame->wHeight,
                                        cands[i].fps) == UVC_SUCCESS) {
      chosen = i;
      break;
    }
  }
  if (chosen < 0) {
    LOGE("startStream: camera rejected every mode");
    goto done;
  }
  result[0] = cands[chosen].frame->wWidth;
  result[1] = cands[chosen].frame->wHeight;
  result[2] = cands[chosen].fps;

  if (uvc_stream_open_ctrl(g_devh, &g_strmh, &ctrl) < 0) {
    LOGE("uvc_stream_open_ctrl failed");
    g_strmh = NULL;
    goto done;
  }
  if (uvc_stream_start(g_strmh, NULL, NULL, 0) < 0) {
    LOGE("uvc_stream_start failed");
    uvc_stream_close(g_strmh);
    g_strmh = NULL;
    goto done;
  }
  memcpy(g_active, result, sizeof(result));
  LOGI("stream %dx%d@%d", result[0], result[1], result[2]);
  got = 1;
done:
  free(cands);
  pthread_mutex_unlock(&g_lock);

  jintArray out = (*env)->NewIntArray(env, got ? 3 : 0);
  if (out && got) (*env)->SetIntArrayRegion(env, out, 0, 3, result);
  return out;
}

JNIEXPORT void JNICALL JNI_FN(stopStream)(JNIEnv *env, jobject thiz) {
  (void)env;
  (void)thiz;
  pthread_mutex_lock(&g_lock);
  stop_stream_locked();
  pthread_mutex_unlock(&g_lock);
}

/* A zero-filled payload (Android isochronous fault, see room-surveillance
 * CLAUDE.md) still starts with SOI, so also require EOI and reject frames
 * whose entropy-coded data is mostly 0x00. */
static int frame_looks_valid(const uint8_t *d, size_t n) {
  if (n < 1024 || d[0] != 0xFF || d[1] != 0xD8)
    return 0;
  size_t tail = n > 4096 ? n - 4096 : 0;
  int has_eoi = 0;
  for (size_t i = n - 1; i > tail; i--) {
    if (d[i - 1] == 0xFF && d[i] == 0xD9) {
      has_eoi = 1;
      break;
    }
  }
  if (!has_eoi)
    return 0;
  size_t from = n / 4, to = n - n / 4, zeros = 0;
  for (size_t i = from; i < to; i++) zeros += d[i] == 0;
  return zeros * 2 < (to - from);
}

/* Waits up to timeoutMs for the next frame and returns its MJPEG bytes (a
 * standard Huffman table is spliced in when the camera omits it, so the file
 * opens in any viewer). Returns null on timeout or an invalid frame. */
JNIEXPORT jbyteArray JNICALL JNI_FN(grabFrame)(JNIEnv *env, jobject thiz, jint timeoutMs) {
  (void)thiz;
  jbyteArray out = NULL;
  /* Tanpa g_lock: start/stop/grab stream semuanya dari thread kamera Kotlin
   * yang sama, jadi g_strmh tidak berubah di tengah panggilan ini. g_lock
   * hanya melindungi g_devh dari kontrol gambar (thread lain) — kalau grab
   * memegangnya, slider tertahan sampai 500 ms per frame. */
  if (!g_strmh)
    goto done;
  uvc_frame_t *fr = NULL;
  uvc_error_t res = uvc_stream_get_frame(g_strmh, &fr, timeoutMs > 0 ? timeoutMs * 1000 : 100000);
  if (res != UVC_SUCCESS || !fr || !fr->data || fr->data_bytes == 0)
    goto done;

  const uint8_t *d = (const uint8_t *)fr->data;
  size_t n = fr->data_bytes;
  if (!frame_looks_valid(d, n))
    goto done;

  int has_dht = 1;
  size_t sos = 0;
  if (find_jpeg_markers(d, n, &has_dht, &sos) && !has_dht) {
    size_t dht_len = 0;
    const uint8_t *dht = mjpeg_get_default_dht_segment(&dht_len);
    out = (*env)->NewByteArray(env, (jsize)(n + dht_len));
    if (out) {
      (*env)->SetByteArrayRegion(env, out, 0, (jsize)sos, (const jbyte *)d);
      (*env)->SetByteArrayRegion(env, out, (jsize)sos, (jsize)dht_len, (const jbyte *)dht);
      (*env)->SetByteArrayRegion(env, out, (jsize)(sos + dht_len), (jsize)(n - sos),
                                 (const jbyte *)(d + sos));
    }
  } else {
    out = (*env)->NewByteArray(env, (jsize)n);
    if (out) (*env)->SetByteArrayRegion(env, out, 0, (jsize)n, (const jbyte *)d);
  }
done:
  return out;
}

JNIEXPORT jstring JNICALL JNI_FN(getLogText)(JNIEnv *env, jobject thiz) {
  (void)thiz;
  char *text = ring_log_get_text();
  jstring s = (*env)->NewStringUTF(env, text ? text : "");
  free(text);
  return s;
}

/* ================= Kontrol gambar UVC =================
 * Urutan id harus sama dengan ImageControl.kt. Tiap kontrol dikembalikan
 * sebagai 5 int: supported, min, max, default, current. */
enum {
  C_BRIGHTNESS, C_CONTRAST, C_SATURATION, C_SHARPNESS, C_GAMMA, C_HUE, C_GAIN,
  C_WB_TEMP, C_EXPOSURE, C_BACKLIGHT, C_POWERLINE, C_COUNT
};

#define READ_CTRL(fn, T)                                                        \
  do {                                                                          \
    T v;                                                                        \
    if (fn(g_devh, &v, UVC_GET_MIN) == UVC_SUCCESS) r[1] = (int)v; else return; \
    if (fn(g_devh, &v, UVC_GET_MAX) == UVC_SUCCESS) r[2] = (int)v; else return; \
    if (fn(g_devh, &v, UVC_GET_DEF) == UVC_SUCCESS) r[3] = (int)v;              \
    if (fn(g_devh, &v, UVC_GET_CUR) == UVC_SUCCESS) r[4] = (int)v;              \
    r[0] = 1;                                                                   \
  } while (0)

static void read_control(int id, int r[5]) {
  memset(r, 0, sizeof(int) * 5);
  switch (id) {
    case C_BRIGHTNESS: READ_CTRL(uvc_get_brightness, int16_t); break;
    case C_CONTRAST: READ_CTRL(uvc_get_contrast, uint16_t); break;
    case C_SATURATION: READ_CTRL(uvc_get_saturation, uint16_t); break;
    case C_SHARPNESS: READ_CTRL(uvc_get_sharpness, uint16_t); break;
    case C_GAMMA: READ_CTRL(uvc_get_gamma, uint16_t); break;
    case C_HUE: READ_CTRL(uvc_get_hue, int16_t); break;
    case C_GAIN: READ_CTRL(uvc_get_gain, uint16_t); break;
    case C_WB_TEMP: READ_CTRL(uvc_get_white_balance_temperature, uint16_t); break;
    case C_EXPOSURE: READ_CTRL(uvc_get_exposure_abs, uint32_t); break;
    case C_BACKLIGHT: READ_CTRL(uvc_get_backlight_compensation, uint16_t); break;
    case C_POWERLINE: READ_CTRL(uvc_get_power_line_frequency, uint8_t); break;
    default: break;
  }
}

/* AE mode bitmap (UVC 1.5 §4.2.2.1.2): 1 manual, 2 auto, 4 shutter prio, 8 aperture prio. */
static uint8_t ae_auto_mode(void) {
  uint8_t res = 0;
  if (uvc_get_ae_mode(g_devh, &res, UVC_GET_RES) != UVC_SUCCESS || !res) res = 0x0A;
  return (res & 0x08) ? 0x08 : 0x02; /* kebanyakan webcam UVC hanya aperture priority */
}

/* [count*5 nilai kontrol] + [aeSupported, aeAuto, awbSupported, awbAuto]. */
JNIEXPORT jintArray JNICALL JNI_FN(getControls)(JNIEnv *env, jobject thiz) {
  (void)thiz;
  jint vals[C_COUNT * 5 + 4];
  memset(vals, 0, sizeof(vals));
  pthread_mutex_lock(&g_lock);
  if (g_devh) {
    for (int i = 0; i < C_COUNT; i++) {
      int r[5];
      read_control(i, r);
      for (int k = 0; k < 5; k++) vals[i * 5 + k] = r[k];
    }
    uint8_t ae = 0, awb = 0;
    if (uvc_get_ae_mode(g_devh, &ae, UVC_GET_CUR) == UVC_SUCCESS) {
      vals[C_COUNT * 5 + 0] = 1;
      vals[C_COUNT * 5 + 1] = (ae & 0x01) ? 0 : 1;
    }
    if (uvc_get_white_balance_temperature_auto(g_devh, &awb, UVC_GET_CUR) == UVC_SUCCESS) {
      vals[C_COUNT * 5 + 2] = 1;
      vals[C_COUNT * 5 + 3] = awb ? 1 : 0;
    }
  }
  pthread_mutex_unlock(&g_lock);
  jintArray out = (*env)->NewIntArray(env, C_COUNT * 5 + 4);
  if (out) (*env)->SetIntArrayRegion(env, out, 0, C_COUNT * 5 + 4, vals);
  return out;
}

JNIEXPORT jboolean JNICALL JNI_FN(setControl)(JNIEnv *env, jobject thiz, jint id, jint value) {
  (void)env;
  (void)thiz;
  uvc_error_t r = UVC_ERROR_NOT_SUPPORTED;
  pthread_mutex_lock(&g_lock);
  if (g_devh) {
    int cur[5];
    read_control(id, cur);
    if (cur[0]) {
      int v = value < cur[1] ? cur[1] : value > cur[2] ? cur[2] : value;
      switch (id) {
        case C_BRIGHTNESS: r = uvc_set_brightness(g_devh, (int16_t)v); break;
        case C_CONTRAST: r = uvc_set_contrast(g_devh, (uint16_t)v); break;
        case C_SATURATION: r = uvc_set_saturation(g_devh, (uint16_t)v); break;
        case C_SHARPNESS: r = uvc_set_sharpness(g_devh, (uint16_t)v); break;
        case C_GAMMA: r = uvc_set_gamma(g_devh, (uint16_t)v); break;
        case C_HUE: r = uvc_set_hue(g_devh, (int16_t)v); break;
        case C_GAIN: r = uvc_set_gain(g_devh, (uint16_t)v); break;
        case C_WB_TEMP: r = uvc_set_white_balance_temperature(g_devh, (uint16_t)v); break;
        case C_EXPOSURE: r = uvc_set_exposure_abs(g_devh, (uint32_t)v); break;
        case C_BACKLIGHT: r = uvc_set_backlight_compensation(g_devh, (uint16_t)v); break;
        case C_POWERLINE: r = uvc_set_power_line_frequency(g_devh, (uint8_t)v); break;
        default: break;
      }
    }
  }
  pthread_mutex_unlock(&g_lock);
  if (r != UVC_SUCCESS) LOGE("setControl %d=%d gagal: %d", id, value, r);
  return r == UVC_SUCCESS;
}

JNIEXPORT jboolean JNICALL JNI_FN(setAutoExposure)(JNIEnv *env, jobject thiz, jboolean on) {
  (void)env;
  (void)thiz;
  uvc_error_t r = UVC_ERROR_NOT_SUPPORTED;
  pthread_mutex_lock(&g_lock);
  if (g_devh) r = uvc_set_ae_mode(g_devh, on ? ae_auto_mode() : 0x01);
  pthread_mutex_unlock(&g_lock);
  return r == UVC_SUCCESS;
}

JNIEXPORT jboolean JNICALL JNI_FN(setAutoWhiteBalance)(JNIEnv *env, jobject thiz, jboolean on) {
  (void)env;
  (void)thiz;
  uvc_error_t r = UVC_ERROR_NOT_SUPPORTED;
  pthread_mutex_lock(&g_lock);
  if (g_devh) r = uvc_set_white_balance_temperature_auto(g_devh, on ? 1 : 0);
  pthread_mutex_unlock(&g_lock);
  return r == UVC_SUCCESS;
}
