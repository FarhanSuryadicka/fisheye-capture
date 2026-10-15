#include "uvc_growable_buf.h"

#include <stdlib.h>

int grow_buf(uint8_t **buf, size_t *cap, size_t need) {
  if (*cap >= need)
    return 1;
  uint8_t *grown = (uint8_t *)realloc(*buf, need);
  if (!grown)
    return 0;
  *buf = grown;
  *cap = need;
  return 1;
}
