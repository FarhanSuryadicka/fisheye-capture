#ifndef UVCSERVER_UVC_MJPEG_DHT_FIX_H
#define UVCSERVER_UVC_MJPEG_DHT_FIX_H

#include <stddef.h>
#include <stdint.h>

/* MJPEG "missing Huffman table" quirk fix — some UVC cameras omit the DHT
 * marker from every frame to save USB bandwidth, since the table never
 * changes; the decoder is expected to supply the standard one itself.
 * libuvc's own libjpeg-based decoder (frame-mjpeg.c's insert_huff_tables,
 * unused by our turbojpeg path) patches libjpeg's internal table pointers
 * directly after jpeg_read_header() — turbojpeg's API exposes no
 * equivalent hook, so instead we detect a missing DHT ourselves and splice
 * a standard one into the raw bytes before decoding. */

/* Walks JPEG markers from SOI looking for a DHT (0xFFC4) before the first
 * SOS (0xFFDA). Returns 1 and sets *sos_off if SOS was found (whether or
 * not a DHT preceded it); returns 0 if the byte stream doesn't even look
 * like a well-formed JPEG (missing/misplaced SOI, truncated marker) — that
 * case is left for turbojpeg's own error reporting to describe, since it's
 * a sign of frame corruption this fix can't do anything about. */
int find_jpeg_markers(const uint8_t *d, size_t n, int *has_dht, size_t *sos_off);

/* Builds the standard DHT marker segment once and caches it internally for
 * reuse — returns a pointer to that cached segment and writes its length to
 * *out_len. Only ever called from preview_thread, so no locking needed. */
const uint8_t *mjpeg_get_default_dht_segment(size_t *out_len);

#endif /* UVCSERVER_UVC_MJPEG_DHT_FIX_H */
