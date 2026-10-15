#include "uvc_mode_negotiation.h"

#include <stdlib.h>

#include "log_ring.h"

static int cmp_candidates(const void *a, const void *b) {
  const mode_candidate_t *ca = a, *cb = b;
  if (ca->score < cb->score)
    return 1;
  if (ca->score > cb->score)
    return -1;
  return 0;
}

/* Doubles *cands's capacity (from *cap) to fit at least nc+1 entries.
 * Returns 0 (leaving *cands and *cap untouched) on OOM so the caller can bail
 * out and use whatever was collected so far instead of losing the buffer. */
static int grow_candidates(mode_candidate_t **cands, int *cap) {
  int new_cap = *cap * 2;
  mode_candidate_t *grown =
      (mode_candidate_t *)realloc(*cands, sizeof(mode_candidate_t) * (size_t)new_cap);
  if (!grown)
    return 0;
  *cands = grown;
  *cap = new_cap;
  return 1;
}

mode_candidate_t *collect_modes(uvc_device_handle_t *devh, int *out_nc) {
  int cap = 64;
  int nc = 0;
  mode_candidate_t *cands =
      (mode_candidate_t *)malloc(sizeof(mode_candidate_t) * (size_t)cap);
  if (!cands) {
    LOGE("collect_modes: OOM allocating initial %d-candidate buffer", cap);
    *out_nc = 0;
    return NULL;
  }

  const uvc_format_desc_t *fmt;
  for (fmt = uvc_get_format_descs(devh); fmt; fmt = fmt->next) {
    /* MJPEG only — preview_thread decodes fr->data with turbojpeg, which
     * expects an encoded JPEG, not a raw YUYV pixel buffer. Never add
     * UVC_VS_FORMAT_UNCOMPRESSED here without adding a YUYV decode path
     * (e.g. libyuv) to preview_thread first. */
    if (fmt->bDescriptorSubtype != UVC_VS_FORMAT_MJPEG)
      continue;
    enum uvc_frame_format ff = UVC_FRAME_FORMAT_MJPEG;
    for (const uvc_frame_desc_t *f = fmt->frame_descs; f; f = f->next) {
      if (!f->wWidth || !f->wHeight)
        continue;
      /* Discrete interval list: one candidate per offered fps (not just the
       * fastest) so callers can ask for an exact width/height/fps match —
       * e.g. a sensor's high-res mode capping at 20fps instead of 30. */
      if (f->bFrameIntervalType > 0 && f->intervals) {
        for (int ivi = 0; ivi < f->bFrameIntervalType; ivi++) {
          uint32_t interval = f->intervals[ivi];
          if (!interval)
            continue;
          int fps = (int)(10000000ULL / interval);
          if (fps <= 0)
            continue;
          if (nc >= cap && !grow_candidates(&cands, &cap)) {
            LOGE("collect_modes: OOM growing past %d candidates, stopping enumeration", nc);
            goto done;
          }
          /* Keep the real advertised fps for negotiation — capping it here
           * would ask uvc_get_stream_ctrl_format_size() for an fps the
           * device never offered, and it'd reject the request. Cap only the
           * score so ultra-high-fps modes don't win auto-best selection. */
          int score_fps = fps > 60 ? 60 : fps;
          cands[nc].format = ff;
          cands[nc].frame = f;
          cands[nc].fps = fps;
          cands[nc].score = (uint64_t)f->wWidth * f->wHeight * score_fps;
          nc++;
        }
        continue;
      }
      uint32_t interval = f->dwDefaultFrameInterval;
      if (f->dwMinFrameInterval)
        interval = f->dwMinFrameInterval;
      if (!interval)
        continue;
      int fps = (int)(10000000ULL / interval);
      if (fps <= 0)
        continue;
      if (nc >= cap && !grow_candidates(&cands, &cap)) {
        LOGE("collect_modes: OOM growing past %d candidates, stopping enumeration", nc);
        goto done;
      }
      int score_fps = fps > 60 ? 60 : fps;
      cands[nc].format = ff;
      cands[nc].frame = f;
      cands[nc].fps = fps;
      cands[nc].score = (uint64_t)f->wWidth * f->wHeight * score_fps;
      nc++;
    }
  }
done:
  qsort(cands, nc, sizeof(*cands), cmp_candidates);
  *out_nc = nc;
  return cands;
}

int find_mode_start_index(const mode_candidate_t *cands, int nc, int capW,
                           int capH, int capFps) {
  if (capW <= 0 || capH <= 0)
    return 0;
  int exact_wh_fps = -1, exact_wh_any_fps = -1;
  for (int i = 0; i < nc; i++) {
    if (cands[i].frame->wWidth != capW || cands[i].frame->wHeight != capH)
      continue;
    if (exact_wh_any_fps < 0)
      exact_wh_any_fps = i;
    if (capFps > 0 && cands[i].fps == capFps) {
      exact_wh_fps = i;
      break;
    }
  }
  if (exact_wh_fps >= 0)
    return exact_wh_fps;
  if (exact_wh_any_fps >= 0)
    return exact_wh_any_fps;
  LOGE("no %dx%d mode found, falling back to auto-best", capW, capH);
  return 0;
}
