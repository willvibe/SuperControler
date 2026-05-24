#include <jni.h>
#include <string.h>
#include <stdio.h>
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <android/log.h>

#define LOG_TAG "UinputBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static int uinput_fd = -1;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_yourapp_remotectrl_input_UinputInjector_nativeInit(JNIEnv *env, jobject thiz, jint width, jint height) {
    uinput_fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (uinput_fd < 0) {
        LOGE("Failed to open /dev/uinput: %s", strerror(errno));
        return JNI_FALSE;
    }

    ioctl(uinput_fd, UI_SET_EVBIT, EV_KEY);
    ioctl(uinput_fd, UI_SET_EVBIT, EV_ABS);
    ioctl(uinput_fd, UI_SET_EVBIT, EV_SYN);

    ioctl(uinput_fd, UI_SET_KEYBIT, BTN_TOUCH);
    ioctl(uinput_fd, UI_SET_KEYBIT, BTN_TOOL_FINGER);

    ioctl(uinput_fd, UI_SET_ABSBIT, ABS_X);
    ioctl(uinput_fd, UI_SET_ABSBIT, ABS_Y);
    ioctl(uinput_fd, UI_SET_ABSBIT, ABS_MT_SLOT);
    ioctl(uinput_fd, UI_SET_ABSBIT, ABS_MT_TRACKING_ID);
    ioctl(uinput_fd, UI_SET_ABSBIT, ABS_MT_POSITION_X);
    ioctl(uinput_fd, UI_SET_ABSBIT, ABS_MT_POSITION_Y);
    ioctl(uinput_fd, UI_SET_ABSBIT, ABS_MT_PRESSURE);
    ioctl(uinput_fd, UI_SET_ABSBIT, ABS_MT_TOUCH_MAJOR);

    struct uinput_user_dev uidev;
    memset(&uidev, 0, sizeof(uidev));
    snprintf(uidev.name, UINPUT_MAX_NAME_SIZE, "SuperControler_Virtual_Touch");
    uidev.id.bustype = BUS_VIRTUAL;
    uidev.id.vendor  = 0x1234;
    uidev.id.product = 0x5678;
    uidev.id.version = 1;

    uidev.absmin[ABS_MT_POSITION_X] = 0;
    uidev.absmax[ABS_MT_POSITION_X] = width;
    uidev.absmin[ABS_MT_POSITION_Y] = 0;
    uidev.absmax[ABS_MT_POSITION_Y] = height;
    uidev.absmin[ABS_X] = 0;
    uidev.absmax[ABS_X] = width;
    uidev.absmin[ABS_Y] = 0;
    uidev.absmax[ABS_Y] = height;
    uidev.absmin[ABS_MT_PRESSURE] = 0;
    uidev.absmax[ABS_MT_PRESSURE] = 255;
    uidev.absmin[ABS_MT_TOUCH_MAJOR] = 0;
    uidev.absmax[ABS_MT_TOUCH_MAJOR] = 255;

    write(uinput_fd, &uidev, sizeof(uidev));

    if (ioctl(uinput_fd, UI_DEV_CREATE) < 0) {
        LOGE("Failed to create uinput device: %s", strerror(errno));
        close(uinput_fd);
        uinput_fd = -1;
        return JNI_FALSE;
    }

    LOGI("Uinput device created successfully (%dx%d)", width, height);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_yourapp_remotectrl_input_UinputInjector_nativeInjectEvent(JNIEnv *env, jobject thiz, jshort type, jshort code, jint value) {
    if (uinput_fd < 0) return;
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = type;
    ev.code = code;
    ev.value = value;
    write(uinput_fd, &ev, sizeof(ev));
}

extern "C" JNIEXPORT void JNICALL
Java_com_yourapp_remotectrl_input_UinputInjector_nativeDestroy(JNIEnv *env, jobject thiz) {
    if (uinput_fd >= 0) {
        ioctl(uinput_fd, UI_DEV_DESTROY);
        close(uinput_fd);
        uinput_fd = -1;
        LOGI("Uinput device destroyed");
    }
}
