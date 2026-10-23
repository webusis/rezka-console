/* librezka_pty.so — настоящий PTY для консоли (Android 4.2.2, armv7).
 *
 * Всё на голых syscalls (swi 0), НИ ОДНОЙ зависимости от libc:
 * скомпилировано с -nostdlib, поэтому .so грузится в Bionic без
 * проблем с GLIBC-версиями символов и без каких-либо NEEDED.
 *
 * ptyStart: открывает /dev/ptmx, ioctl TIOCGPTN -> /dev/pts/N,
 * форкается, в ребёнке: setsid + controlling tty + dup2 slave на
 * 0/1/2 + exec заданной команды. Возвращает master-дескриптор.
 */
#include <jni.h>
#include <stdint.h>

/* ---- syscall numbers, ARM EABI ---- */
#define __NR_exit     1
#define __NR_fork     2
#define __NR_read     3
#define __NR_write    4
#define __NR_open     5
#define __NR_close    6
#define __NR_execve   11
#define __NR_ioctl    54
#define __NR_dup2     63
#define __NR_setsid   66

static long sys0(long n) {
    register long r7 asm("r7") = n;
    register long r0 asm("r0");
    asm volatile("swi 0" : "=r"(r0) : "r"(r7) : "memory");
    return r0;
}
static long sys1(long n, long a) {
    register long r7 asm("r7") = n;
    register long r0 asm("r0") = a;
    asm volatile("swi 0" : "+r"(r0) : "r"(r7) : "memory");
    return r0;
}
static long sys2(long n, long a, long b) {
    register long r7 asm("r7") = n;
    register long r0 asm("r0") = a;
    register long r1 asm("r1") = b;
    asm volatile("swi 0" : "+r"(r0) : "r"(r7), "r"(r1) : "memory");
    return r0;
}
static long sys3(long n, long a, long b, long c) {
    register long r7 asm("r7") = n;
    register long r0 asm("r0") = a;
    register long r1 asm("r1") = b;
    register long r2 asm("r2") = c;
    asm volatile("swi 0" : "+r"(r0) : "r"(r7), "r"(r1), "r"(r2) : "memory");
    return r0;
}

/* ioctl-коды, asm-generic (32-bit ARM), сверены с arm-linux-gnueabi заголовками:
 * TIOCGPTN = _IOR('T',0x30,uint) = 0x80045430 (READ)
 * TIOCSPTLCK = _IOW('T',0x31,int) = 0x40045431 (WRITE) */
#define TIOCGPTN    0x80045430
#define TIOCSPTLCK  0x40045431
#define TIOCSCTTY   0x540E
#define TIOCSWINSZ  0x5414

#define O_RDWR 2

/* Нормальное Android-окружение для exec. БЕЗ USER=root: иначе mksh
 * рисует фейковый промпт root@android (mkshrc берёт USER из env,
 * а не из id). Пусть сам честно вычислит. */
static char *android_env[] = {
    "PATH=/sbin:/system/sbin:/system/bin:/system/xbin:/opt/bin",
    "ANDROID_DATA=/data",
    "ANDROID_ROOT=/system",
    "EXTERNAL_STORAGE=/mnt/sdcard",
    "HOME=/data/data/org.rezka.console",
    "SHELL=/system/bin/sh",
    "TERM=xterm",
    "HOSTNAME=android",
    NULL
};

/* "/dev/pts/" + десятичное число без libc и без деления
 * (деление генерит вызов __aeabi_idiv/ldiv0 — лишние символы) */
static void put_num(unsigned n, char *out) {
    char tmp[12];
    int i = 0;
    if (n == 0) tmp[i++] = '0';
    while (n > 0) {
        unsigned tens = 0;
        while (n >= 10) { n -= 10; tens++; }  /* n -> остаток, tens -> десятки */
        tmp[i++] = '0' + n;
        n = tens;
    }
    while (i > 0) *out++ = tmp[--i];
    *out = 0;
}

static void build_pts_path(char *out, int n) {
    const char *pre = "/dev/pts/";
    char *p = out;
    while (*pre) *p++ = *pre++;
    put_num((unsigned) n, p);
}

/* последняя ошибка pty: 0 = успех, иначе -(errno) */
static int g_err = 0;

static int pty_open(int *master, int *slave) {
    g_err = 0;
    int m = (int) sys2(__NR_open, (long) "/dev/ptmx", O_RDWR);
    if (m < 0) { g_err = (int) -m; return -1; }
    unsigned int n = 0;
    long rc = sys3(__NR_ioctl, m, TIOCGPTN, (long) &n);
    if (rc < 0) { g_err = (int) -rc; sys1(__NR_close, m); return -1; }
    int unlock = 0;
    sys3(__NR_ioctl, m, TIOCSPTLCK, (long) &unlock);
    char path[32];
    build_pts_path(path, (int) n);
    int s = (int) sys2(__NR_open, (long) path, O_RDWR);
    if (s < 0) { g_err = (int) -s; sys1(__NR_close, m); return -1; }
    *master = m;
    *slave = s;
    return 0;
}

static void set_winsize(int fd, int rows, int cols) {
    unsigned short ws[4];
    ws[0] = (unsigned short) rows;
    ws[1] = (unsigned short) cols;
    ws[2] = 0;
    ws[3] = 0;
    sys3(__NR_ioctl, fd, TIOCSWINSZ, (long) ws);
}

JNIEXPORT jint JNICALL Java_org_rezka_console_ConsoleService_ptyStart
        (JNIEnv *env, jobject thiz, jstring cmd) {
    int m = 0, s = 0;
    if (pty_open(&m, &s) < 0) return -1;

    const char *path = (*env)->GetStringUTFChars(env, cmd, NULL);
    long pid = sys0(__NR_fork);
    if (pid < 0) {
        g_err = (int) -pid;
        (*env)->ReleaseStringUTFChars(env, cmd, path);
        sys1(__NR_close, m);
        sys1(__NR_close, s);
        return -1;
    }
    if (pid == 0) {
        /* ребёнок: свой терминал */
        sys0(__NR_setsid);
        sys3(__NR_ioctl, s, TIOCSCTTY, 0);
        sys2(__NR_dup2, s, 0);
        sys2(__NR_dup2, s, 1);
        sys2(__NR_dup2, s, 2);
        if (s > 2) sys1(__NR_close, s);
        sys1(__NR_close, m);
        char *argv[2];
        argv[0] = (char *) path;
        argv[1] = NULL;
        sys3(__NR_execve, (long) path, (long) argv, (long) android_env);
        sys1(__NR_exit, 127);
    }
    (*env)->ReleaseStringUTFChars(env, cmd, path);
    sys1(__NR_close, s);
    set_winsize(m, 24, 80);
    return (jint) m;
}

JNIEXPORT jint JNICALL Java_org_rezka_console_ConsoleService_ptyRead
        (JNIEnv *env, jobject thiz, jint fd, jbyteArray buf, jint len) {
    jbyte *b = (*env)->GetByteArrayElements(env, buf, NULL);
    if (b == NULL) return -1;
    long r = sys3(__NR_read, fd, (long) b, len);
    (*env)->ReleaseByteArrayElements(env, buf, b, 0);
    return (jint) r;
}

JNIEXPORT jint JNICALL Java_org_rezka_console_ConsoleService_ptyWrite
        (JNIEnv *env, jobject thiz, jint fd, jbyteArray buf, jint off, jint len) {
    jbyte *b = (*env)->GetByteArrayElements(env, buf, NULL);
    if (b == NULL) return -1;
    long r = sys3(__NR_write, fd, (long) (b + off), len);
    (*env)->ReleaseByteArrayElements(env, buf, b, 0);
    return (jint) r;
}

JNIEXPORT void JNICALL Java_org_rezka_console_ConsoleService_ptyClose
        (JNIEnv *env, jobject thiz, jint fd) {
    sys1(__NR_close, fd);
}

JNIEXPORT void JNICALL Java_org_rezka_console_ConsoleService_ptySetSize
        (JNIEnv *env, jobject thiz, jint fd, jint rows, jint cols) {
    set_winsize(fd, rows, cols);
}

JNIEXPORT jint JNICALL Java_org_rezka_console_ConsoleService_ptyLastError
        (JNIEnv *env, jobject thiz) {
    return (jint) g_err;
}