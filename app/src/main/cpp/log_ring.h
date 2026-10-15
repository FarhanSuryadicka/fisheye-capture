#ifndef UVCSERVER_LOG_RING_H
#define UVCSERVER_LOG_RING_H

/* Shared by log_ring.c (which owns the ring buffer storage/ring_log's
 * definition) and detection.c, so detection's LOGI/LOGE calls land in the
 * same buffer getLogText() already polls — one log surface, not a second
 * one Kotlin would need to know about. */

#include <android/log.h>
#include <stddef.h>
#include <stdint.h>

#include "uvc_growable_buf.h"

#define TAG "uvcserver-native"

/* Defined in log_ring.c (owns g_log/g_log_head/g_log_count/g_log_mutex). */
void ring_log(const char *fmt, ...);

/* Snapshots the ring buffer as one newline-joined, NUL-terminated string —
 * backs the UvcNative_getLogText JNI export, which otherwise has no way to
 * read g_log/g_log_head/g_log_count now that they're private to
 * log_ring.c. Returns a malloc'd string the caller must free(); never NULL
 * (an OOM allocating the main buffer falls back to a small static message,
 * itself still malloc'd so the caller's free() contract stays uniform). */
char *ring_log_get_text(void);

/* grow_buf is declared in uvc_growable_buf.h (defined in
 * uvc_growable_buf.c) — pulled in above so detection.c's letterbox scratch
 * buffer reuses it via this one include, instead of hand-rolling a 6th copy
 * of the same block. */

#define LOGI(...)                                                   \
  do {                                                              \
    __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__);        \
    ring_log(__VA_ARGS__);                                          \
  } while (0)
#define LOGE(...)                                                   \
  do {                                                              \
    __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__);       \
    ring_log(__VA_ARGS__);                                          \
  } while (0)

#endif  /* UVCSERVER_LOG_RING_H */
