#define _POSIX_C_SOURCE 200809L

#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static void throw_with_message(JNIEnv *env, const char *message) {
    jclass cls = (*env)->FindClass(env, "java/io/IOException");
    if (cls != NULL) {
        (*env)->ThrowNew(env, cls, message);
    }
}

static void throw_errno(JNIEnv *env, const char *operation) {
    char message[512];
    int error = errno;
    snprintf(message, sizeof(message), "%s: %s", operation, strerror(error));
    throw_with_message(env, message);
}

static int as_fd(jlong value) {
    if (value < 0 || value > INT32_MAX) {
        errno = EBADF;
        return -1;
    }
    return (int)value;
}

static char *path_from_utf8(JNIEnv *env, jbyteArray encoded) {
    if (encoded == NULL) {
        throw_with_message(env, "filesystem path is required");
        return NULL;
    }

    jsize length = (*env)->GetArrayLength(env, encoded);
    if (length <= 0 || length > 65536) {
        throw_with_message(env, "filesystem path length is invalid");
        return NULL;
    }

    char *path = malloc((size_t)length + 1);
    if (path == NULL) {
        throw_with_message(env, "out of memory while decoding filesystem path");
        return NULL;
    }

    (*env)->GetByteArrayRegion(env, encoded, 0, length, (jbyte *)path);
    if ((*env)->ExceptionCheck(env)) {
        free(path);
        return NULL;
    }

    for (jsize i = 0; i < length; i++) {
        if (path[i] == '\0') {
            free(path);
            throw_with_message(env, "filesystem path cannot contain NUL");
            return NULL;
        }
    }

    path[length] = '\0';
    return path;
}

static int configure_cloexec(int fd) {
#ifdef FD_CLOEXEC
    int flags = fcntl(fd, F_GETFD, 0);
    if (flags < 0) return -1;
    if (fcntl(fd, F_SETFD, flags | FD_CLOEXEC) < 0) return -1;
#endif
    return 0;
}

static int open_regular(JNIEnv *env, const char *path, int flags, mode_t mode) {
    int open_flags = flags;
#ifdef O_CLOEXEC
    open_flags |= O_CLOEXEC;
#endif
#ifdef O_NONBLOCK
    /*
     * Open nonblocking until fstat proves this is a regular file. This keeps
     * a path that names a FIFO/device from pinning a carrier thread before the
     * regular-file policy can reject it.
     */
    open_flags |= O_NONBLOCK;
#endif

    int fd;
    do {
        fd = open(path, open_flags, mode);
    } while (fd < 0 && errno == EINTR);

    if (fd < 0) {
        throw_errno(env, "open");
        return -1;
    }

    if (configure_cloexec(fd) < 0) {
        int saved = errno;
        (void)close(fd);
        errno = saved;
        throw_errno(env, "fcntl(FD_CLOEXEC)");
        return -1;
    }

    struct stat st;
    if (fstat(fd, &st) < 0) {
        int saved = errno;
        (void)close(fd);
        errno = saved;
        throw_errno(env, "fstat");
        return -1;
    }

    if (!S_ISREG(st.st_mode)) {
        (void)close(fd);
        throw_with_message(env, "native_fs only opens regular files");
        return -1;
    }

#ifdef O_NONBLOCK
    int status_flags = fcntl(fd, F_GETFL, 0);
    if (status_flags < 0
            || fcntl(fd, F_SETFL, status_flags & ~O_NONBLOCK) < 0) {
        int saved = errno;
        (void)close(fd);
        errno = saved;
        throw_errno(env, "fcntl(clear O_NONBLOCK)");
        return -1;
    }
#endif

    return fd;
}

JNIEXPORT jlong JNICALL
Java_dev_oreslang_fs_NativeFileBridge_openRead(
        JNIEnv *env,
        jclass cls,
        jbyteArray path_utf8) {
    (void)cls;
    char *path = path_from_utf8(env, path_utf8);
    if (path == NULL) return -1;

    int fd = open_regular(env, path, O_RDONLY, 0);
    free(path);
    return fd < 0 ? -1 : (jlong)fd;
}

JNIEXPORT jlong JNICALL
Java_dev_oreslang_fs_NativeFileBridge_openWriteTruncate(
        JNIEnv *env,
        jclass cls,
        jbyteArray path_utf8) {
    (void)cls;
    char *path = path_from_utf8(env, path_utf8);
    if (path == NULL) return -1;

    int fd = open_regular(env, path, O_WRONLY | O_CREAT | O_TRUNC, 0666);
    free(path);
    return fd < 0 ? -1 : (jlong)fd;
}

JNIEXPORT jlong JNICALL
Java_dev_oreslang_fs_NativeFileBridge_openWriteAppend(
        JNIEnv *env,
        jclass cls,
        jbyteArray path_utf8) {
    (void)cls;
    char *path = path_from_utf8(env, path_utf8);
    if (path == NULL) return -1;

    int fd = open_regular(env, path, O_WRONLY | O_CREAT | O_APPEND, 0666);
    free(path);
    return fd < 0 ? -1 : (jlong)fd;
}

static int validate_range(
        JNIEnv *env,
        jbyteArray bytes,
        jint offset,
        jint length) {
    if (bytes == NULL) {
        throw_with_message(env, "byte array is required");
        return 0;
    }
    jsize size = (*env)->GetArrayLength(env, bytes);
    if (offset < 0 || length < 0 || offset > size || length > size - offset) {
        throw_with_message(env, "byte range is out of bounds");
        return 0;
    }
    return 1;
}

JNIEXPORT jint JNICALL
Java_dev_oreslang_fs_NativeFileBridge_read(
        JNIEnv *env,
        jclass cls,
        jlong raw_fd,
        jbyteArray bytes,
        jint offset,
        jint length) {
    (void)cls;
    int fd = as_fd(raw_fd);
    if (fd < 0) {
        throw_errno(env, "read");
        return -1;
    }
    if (!validate_range(env, bytes, offset, length)) return -1;
    if (length == 0) return 0;

    unsigned char *buffer = malloc((size_t)length);
    if (buffer == NULL) {
        throw_with_message(env, "out of memory while reading file");
        return -1;
    }

    ssize_t count;
    do {
        count = read(fd, buffer, (size_t)length);
    } while (count < 0 && errno == EINTR);

    if (count < 0) {
        free(buffer);
        throw_errno(env, "read");
        return -1;
    }

    if (count > 0) {
        (*env)->SetByteArrayRegion(
                env, bytes, offset, (jsize)count, (const jbyte *)buffer);
        if ((*env)->ExceptionCheck(env)) {
            free(buffer);
            return -1;
        }
    }

    free(buffer);
    return count == 0 ? -1 : (jint)count;
}

JNIEXPORT jint JNICALL
Java_dev_oreslang_fs_NativeFileBridge_write(
        JNIEnv *env,
        jclass cls,
        jlong raw_fd,
        jbyteArray bytes,
        jint offset,
        jint length) {
    (void)cls;
    int fd = as_fd(raw_fd);
    if (fd < 0) {
        throw_errno(env, "write");
        return -1;
    }
    if (!validate_range(env, bytes, offset, length)) return -1;
    if (length == 0) return 0;

    unsigned char *buffer = malloc((size_t)length);
    if (buffer == NULL) {
        throw_with_message(env, "out of memory while writing file");
        return -1;
    }

    (*env)->GetByteArrayRegion(
            env, bytes, offset, length, (jbyte *)buffer);
    if ((*env)->ExceptionCheck(env)) {
        free(buffer);
        return -1;
    }

    ssize_t count;
    do {
        count = write(fd, buffer, (size_t)length);
    } while (count < 0 && errno == EINTR);

    free(buffer);
    if (count < 0) {
        throw_errno(env, "write");
        return -1;
    }
    return (jint)count;
}

JNIEXPORT jlong JNICALL
Java_dev_oreslang_fs_NativeFileBridge_size(
        JNIEnv *env,
        jclass cls,
        jlong raw_fd) {
    (void)cls;
    int fd = as_fd(raw_fd);
    if (fd < 0) {
        throw_errno(env, "fstat");
        return -1;
    }

    struct stat st;
    if (fstat(fd, &st) < 0) {
        throw_errno(env, "fstat");
        return -1;
    }
    if (st.st_size < 0) {
        throw_with_message(env, "file size is negative");
        return -1;
    }
    return (jlong)st.st_size;
}

JNIEXPORT void JNICALL
Java_dev_oreslang_fs_NativeFileBridge_fsync(
        JNIEnv *env,
        jclass cls,
        jlong raw_fd) {
    (void)cls;
    int fd = as_fd(raw_fd);
    if (fd < 0) {
        throw_errno(env, "fsync");
        return;
    }

    int rc;
    do {
        rc = fsync(fd);
    } while (rc < 0 && errno == EINTR);

    if (rc < 0) throw_errno(env, "fsync");
}

JNIEXPORT void JNICALL
Java_dev_oreslang_fs_NativeFileBridge_close(
        JNIEnv *env,
        jclass cls,
        jlong raw_fd) {
    (void)cls;
    int fd = as_fd(raw_fd);
    if (fd < 0) {
        throw_errno(env, "close");
        return;
    }

    /*
     * Do not retry close(2) after EINTR. On common POSIX systems the fd may
     * already have been released, and retrying can close an unrelated reused
     * descriptor.
     */
    if (close(fd) < 0 && errno != EINTR) {
        throw_errno(env, "close");
    }
}

JNIEXPORT void JNICALL
Java_dev_oreslang_fs_NativeFileBridge_removeFile(
        JNIEnv *env,
        jclass cls,
        jbyteArray path_utf8) {
    (void)cls;
    char *path = path_from_utf8(env, path_utf8);
    if (path == NULL) return;

    int rc;
    do {
        rc = unlink(path);
    } while (rc < 0 && errno == EINTR);

    free(path);
    if (rc < 0) throw_errno(env, "unlink");
}
