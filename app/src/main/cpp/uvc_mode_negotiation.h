#ifndef UVCSERVER_UVC_MODE_NEGOTIATION_H
#define UVCSERVER_UVC_MODE_NEGOTIATION_H

#include <libuvc/libuvc.h>
#include <stdint.h>

/* Mode selection (same scoring as the Windows mjpegserver). */
typedef struct {
  enum uvc_frame_format format;
  const uvc_frame_desc_t *frame;
  int fps;
  uint64_t score;
} mode_candidate_t;

/* Returns a malloc'd, best-score-first array of every MJPEG (format,
 * frame-size, fps) mode the device advertises, via *out_nc — the caller
 * must free() it regardless of *out_nc (0 candidates still returns a valid,
 * freeable pointer). Returns NULL only on OOM allocating the initial
 * buffer. */
mode_candidate_t *collect_modes(uvc_device_handle_t *devh, int *out_nc);

/* cands is sorted best-score-first, so index 0 is the "auto-best" (usually
 * highest resolution) mode. capW/capH/capFps (any may be 0 = don't care)
 * let a caller pin a known-good mode instead of always getting auto-best.
 * Returns the cands[] index to start the uvc_get_stream_ctrl_format_size
 * negotiation loop from. */
int find_mode_start_index(const mode_candidate_t *cands, int nc, int capW,
                           int capH, int capFps);

#endif /* UVCSERVER_UVC_MODE_NEGOTIATION_H */
