/* Runs inside the same PRoot namespace as DSH. Only fixed fields and package
 * paths go to the supervisor. No file contents or child environment are emitted. */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/sysmacros.h>
#include <unistd.h>

static void fail(const char *operation, const char *path, int err) {
    fprintf(stderr, "DSH_CHECK\t%s\t%s\t%d\n", operation, path, err);
    exit(125);
}

static void check_binding(const char *library, const char *guest) {
    char installed[PATH_MAX];
    struct stat source, target;
    snprintf(installed, sizeof installed, "/run/apk-libs/%s", library);
    if (stat(installed, &source) < 0) fail("stat", installed, errno);
    int fd = openat(AT_FDCWD, guest, O_RDONLY | O_CLOEXEC);
    if (fd < 0) fail("openat", guest, errno);
    if (fstat(fd, &target) < 0) fail("fstat", guest, errno);
    if (!S_ISREG(target.st_mode) || !target.st_size ||
        target.st_dev != source.st_dev || target.st_ino != source.st_ino)
        fail("binding", guest, 0);
    void *page = mmap(NULL, 1, PROT_READ | PROT_EXEC, MAP_PRIVATE, fd, 0);
    if (page == MAP_FAILED) fail("mmap", guest, errno);
    munmap(page, 1);
    close(fd);
}

struct identity { dev_t dev; ino_t ino; };

/* Match libuv's statx-first lookup, including its POSIX fallback. DSH
 * compares the open descriptor with the lock name before accepting flock. */
static int identity(int fd, const char *path, struct identity *id, const char **op) {
    struct statx x;
    struct stat s;
    *op = "statx";
    if (syscall(SYS_statx, fd, path, fd == AT_FDCWD ? 0 : AT_EMPTY_PATH,
                STATX_BASIC_STATS | STATX_BTIME, &x) == 0) {
        id->dev = makedev(x.stx_dev_major, x.stx_dev_minor);
        id->ino = x.stx_ino;
        return 0;
    }
    if (errno != ENOSYS && errno != EINVAL && errno != EPERM && errno != EOPNOTSUPP) return errno;
    *op = fd == AT_FDCWD ? "stat" : "fstat";
    if ((fd == AT_FDCWD ? stat(path, &s) : fstat(fd, &s)) < 0) return errno;
    id->dev = s.st_dev;
    id->ino = s.st_ino;
    return 0;
}

static void check_data(const char *dir, int session) {
    char path[PATH_MAX], published[PATH_MAX];
    snprintf(path, sizeof path, "%s/.dsh-check-XXXXXX", dir);
    int fd = mkstemp(path);
    if (fd < 0) fail("mkstemp", dir, errno);
    int err = 0;
    const char *op = "write";
    int linked = 0;
    int mismatch = 0;
    if (write(fd, "x", 1) != 1) err = errno ? errno : EIO;
    if (!err && session) {
        op = "flock";
        if (flock(fd, LOCK_EX | LOCK_NB) < 0) err = errno;
        if (!err) {
            struct identity held, named;
            err = identity(fd, "", &held, &op);
            if (!err) err = identity(AT_FDCWD, path, &named, &op);
            if (!err) mismatch = held.dev != named.dev || held.ino != named.ino;
        }
        if (mismatch) {
            close(fd);
            unlink(path);
            fail("lock_identity", dir, 0);
        }
        if (!err) {
            op = "linkat";
            if (snprintf(published, sizeof published, "%s.link", path) >= (int)sizeof published)
                err = ENAMETOOLONG;
            else if (linkat(AT_FDCWD, path, AT_FDCWD, published, 0) < 0) err = errno;
            else linked = 1;
        }
        if (linked && (linkat(AT_FDCWD, path, AT_FDCWD, published, 0) == 0 || errno != EEXIST))
            err = EIO;
    }
    close(fd);
    if (unlink(path) < 0 && !err) { op = "unlink"; err = errno; }
    if (linked) {
        int saved = openat(AT_FDCWD, published, O_RDONLY);
        if (saved < 0 && !err) { op = "openat"; err = errno; }
        if (saved >= 0) {
            char byte;
            if (read(saved, &byte, 1) != 1 || byte != 'x') { op = "read"; err = EIO; }
            close(saved);
        }
        if (unlink(published) < 0 && !err) { op = "unlink"; err = errno; }
    }
    if (err && strcmp(op, "linkat") == 0) {
        fprintf(stderr, "DSH_CHECK\tlinkat\t%s\t%d\t%s\n", path, err, published);
        exit(125);
    }
    if (err) fail(op, path, err);
}

int main(int argc, char **argv) {
    int i = 1;
    while (i + 1 < argc && strcmp(argv[i], "--") != 0) {
        check_binding(argv[i], argv[i + 1]);
        i += 2;
    }
    if (i + 1 >= argc || strcmp(argv[i], "--") != 0) return 125;
    check_data("/home/dsh", 0);
    check_data("/home/dsh/.dsh", 0);
    check_data("/home/dsh/.dsh/sessions", 1);
    check_data("/home/dsh/chat", 0);
    check_data("/tmp", 0);
    check_data("/opt/pkg-cache", 0);
    fputs("DSH_CHECK_OK\n", stderr);
    /* Now the busybox, the dynamic interpreter and DSH are verified bindings. */
    execv(argv[i + 1], argv + i + 1);
    fail("execve", argv[i + 1], errno);
}
