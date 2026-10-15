#ifndef UVCSERVER_UVC_GROWABLE_BUF_H
#define UVCSERVER_UVC_GROWABLE_BUF_H

#include <stddef.h>
#include <stdint.h>

/* Grows *buf (tracked by *cap) via realloc to at least need bytes, if it
 * isn't already — leaves buf/cap valid on OOM so the caller can bail out
 * instead of losing the buffer. Returns 1 (buf/cap already sufficient, or
 * just grown) on success, 0 (buf/cap left untouched) on OOM. Collapses what
 * used to be five hand-rolled copies of this exact realloc-if-too-small
 * block — see docs/august-20/improvement-10.md item D1.
 *
 * need == 0 is a no-op that returns 1 without ever allocating — on a fresh
 * buf/cap pair (NULL/0), that leaves *buf NULL rather than a valid
 * zero-length allocation. Every current call site guards its own w/h > 0 (or
 * equivalent) before computing need, so this is never actually hit today; a
 * future call site must do the same, not pass need == 0 expecting a non-NULL
 * buf back. See docs/september-7/improvement-18.md item B2. */
int grow_buf(uint8_t **buf, size_t *cap, size_t need);

#endif /* UVCSERVER_UVC_GROWABLE_BUF_H */
