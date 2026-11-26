// Acesso ao evdev para o servidor shell do Klyro: abrir um /dev/input/eventN e "agarrar"
// (EVIOCGRAB) o dispositivo, para o mouse travado não mexer o cursor nem clicar no jogo.
// Mesma técnica do XtMapper (GPL-3.0).
#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <linux/input.h>

JNIEXPORT jint JNICALL
Java_gg_klyro_mapper_server_Evdev_open(JNIEnv *env, jclass clazz, jstring path) {
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    int fd = open(p, O_RDONLY | O_CLOEXEC);
    (*env)->ReleaseStringUTFChars(env, path, p);
    return fd;
}

JNIEXPORT jboolean JNICALL
Java_gg_klyro_mapper_server_Evdev_grab(JNIEnv *env, jclass clazz, jint fd, jboolean on) {
    return ioctl(fd, EVIOCGRAB, on ? 1 : 0) == 0;
}

JNIEXPORT void JNICALL
Java_gg_klyro_mapper_server_Evdev_close(JNIEnv *env, jclass clazz, jint fd) {
    close(fd);
}

// Valor máximo de um eixo ABS (ex.: ABS_MT_POSITION_X), para converter o touch em pixels da tela.
JNIEXPORT jint JNICALL
Java_gg_klyro_mapper_server_Evdev_absMax(JNIEnv *env, jclass clazz, jint fd, jint code) {
    struct input_absinfo info;
    if (ioctl(fd, EVIOCGABS(code), &info) < 0) return -1;
    return info.maximum;
}
