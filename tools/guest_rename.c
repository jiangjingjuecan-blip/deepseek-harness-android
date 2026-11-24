/* Guest glibc on x86_64 uses the legacy rename syscall. Android apps use renameat for
 * the same atomic replacement within their private files. No file access policy changes. */
#define _GNU_SOURCE
#include <fcntl.h>
#include <stdio.h>

int rename(const char *from, const char *to) {
    return renameat(AT_FDCWD, from, AT_FDCWD, to);
}
