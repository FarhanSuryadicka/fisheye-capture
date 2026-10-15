#include "log_ring.h"

#include <pthread.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* In-app log ring buffer (mirrored to logcat) — the phone's USB port is
 * occupied by the camera, so `adb logcat` over USB isn't an option while
 * streaming; the UI polls getLogText() instead. Macros/TAG declared in
 * log_ring.h — shared with detection.c so its logging lands in the same
 * buffer. */
#define LOG_LINES 200
#define LOG_LINE_LEN 200
static char g_log[LOG_LINES][LOG_LINE_LEN];
static int g_log_head = 0;
static int g_log_count = 0;
static pthread_mutex_t g_log_mutex = PTHREAD_MUTEX_INITIALIZER;

void ring_log(const char *fmt, ...) {
  char line[LOG_LINE_LEN];
  va_list ap;
  va_start(ap, fmt);
  vsnprintf(line, sizeof(line), fmt, ap);
  va_end(ap);

  pthread_mutex_lock(&g_log_mutex);
  strncpy(g_log[g_log_head], line, LOG_LINE_LEN - 1);
  g_log[g_log_head][LOG_LINE_LEN - 1] = '\0';
  g_log_head = (g_log_head + 1) % LOG_LINES;
  if (g_log_count < LOG_LINES)
    g_log_count++;
  pthread_mutex_unlock(&g_log_mutex);
}

/* Worst-case size, allocated before the lock is taken: LOG_LINES/
 * LOG_LINE_LEN are compile-time constants, so there is no need for a
 * separate locked measurement pass just to size this malloc. Every LOGI/
 * LOGE call in this process (ring_log(), same g_log_mutex) would otherwise
 * block behind this call's malloc — see docs/august-21/improvement-14.md
 * item B. Returns a malloc'd, newline-joined, NUL-terminated string the
 * caller must free(); never NULL (an OOM allocating it returns a small
 * static message instead). */
char *ring_log_get_text(void) {
  char *buf = (char *)malloc((size_t)LOG_LINES * LOG_LINE_LEN + 1);
  if (!buf) {
    char *fallback = (char *)malloc(16);
    if (fallback)
      strcpy(fallback, "log buffer OOM");
    return fallback;
  }
  pthread_mutex_lock(&g_log_mutex);
  int start = (g_log_head - g_log_count + LOG_LINES) % LOG_LINES;
  char *p = buf;
  for (int i = 0; i < g_log_count; i++) {
    const char *line = g_log[(start + i) % LOG_LINES];
    size_t len = strlen(line);
    memcpy(p, line, len);
    p += len;
    *p++ = '\n';
  }
  *p = '\0';
  pthread_mutex_unlock(&g_log_mutex);
  return buf;
}
