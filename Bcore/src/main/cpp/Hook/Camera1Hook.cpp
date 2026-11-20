#include "Camera1Hook.h"

#include <android/log.h>
#include <cstring>
#include <jni.h>

#include "JniHook/JniHook.h"

#define VCAM_OPEN "VCAM_OPEN"

static jclass gInterceptClass = nullptr;
static jmethodID gInterceptSetup = nullptr;
static jmethodID gPreviewTarget = nullptr;
static jmethodID gGetParameters = nullptr;
static jmethodID gSetParameters = nullptr;
static jmethodID gStartPreview = nullptr;
static jmethodID gStopPreview = nullptr;
static jmethodID gRelease = nullptr;

typedef jint (*Setup36Fn)(JNIEnv *, jobject, jobject, jint, jint, jboolean, jobject, jint);
typedef jint (*Setup35Fn)(JNIEnv *, jobject, jobject, jint, jstring, jint, jboolean, jint, jint);
typedef jint (*Setup34Fn)(JNIEnv *, jobject, jobject, jint, jstring, jboolean, jboolean);
typedef jint (*SetupLegacyFn)(JNIEnv *, jobject, jobject, jint, jint, jstring, jboolean);
typedef void (*TextureFn)(JNIEnv *, jobject, jobject);
typedef void (*SurfaceFn)(JNIEnv *, jobject, jobject);
typedef void (*VoidFn)(JNIEnv *, jobject);
typedef jboolean (*BoolFn)(JNIEnv *, jobject);
typedef jstring (*GetParamsFn)(JNIEnv *, jobject);
typedef void (*SetParamsFn)(JNIEnv *, jobject, jstring);
typedef void (*SetOrientationFn)(JNIEnv *, jobject, jint);
typedef void (*TakePictureFn)(JNIEnv *, jobject, jint);
typedef void (*HasCallbackFn)(JNIEnv *, jobject, jboolean, jboolean);
typedef jboolean (*EnableShutterFn)(JNIEnv *, jobject, jboolean);

static Setup36Fn orig_setup36 = nullptr;
static Setup35Fn orig_setup35 = nullptr;
static Setup34Fn orig_setup34 = nullptr;
static SetupLegacyFn orig_setup_legacy = nullptr;
static TextureFn orig_set_preview_texture = nullptr;
static SurfaceFn orig_set_preview_surface = nullptr;
static VoidFn orig_start_preview = nullptr;
static VoidFn orig_stop_preview = nullptr;
static VoidFn orig_native_release = nullptr;
static BoolFn orig_preview_enabled = nullptr;
static GetParamsFn orig_get_parameters = nullptr;
static SetParamsFn orig_set_parameters = nullptr;
static SetOrientationFn orig_set_display_orientation = nullptr;
static VoidFn orig_auto_focus = nullptr;
static VoidFn orig_cancel_auto_focus = nullptr;
static TakePictureFn orig_take_picture = nullptr;
static HasCallbackFn orig_has_preview_callback = nullptr;
static EnableShutterFn orig_enable_shutter = nullptr;
static VoidFn orig_lock = nullptr;
static VoidFn orig_unlock = nullptr;
static VoidFn orig_reconnect = nullptr;

static void clearException(JNIEnv *env) {
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
}

static bool callInterceptSetup(JNIEnv *env, jobject camera, jint cameraId) {
    if (gInterceptClass == nullptr || gInterceptSetup == nullptr || camera == nullptr) {
        return false;
    }
    jboolean intercepted = env->CallStaticBooleanMethod(gInterceptClass, gInterceptSetup, camera, cameraId);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        return false;
    }
    return intercepted == JNI_TRUE;
}

static void callPreview(JNIEnv *env, jobject camera, jobject target, jboolean texture) {
    if (gInterceptClass == nullptr || gPreviewTarget == nullptr) {
        return;
    }
    env->CallStaticVoidMethod(gInterceptClass, gPreviewTarget, camera, target, texture);
    clearException(env);
}

static void callStart(JNIEnv *env, jobject camera) {
    if (gInterceptClass == nullptr || gStartPreview == nullptr) {
        return;
    }
    env->CallStaticVoidMethod(gInterceptClass, gStartPreview, camera);
    clearException(env);
}

static void callStop(JNIEnv *env, jobject camera) {
    if (gInterceptClass == nullptr || gStopPreview == nullptr) {
        return;
    }
    env->CallStaticVoidMethod(gInterceptClass, gStopPreview, camera);
    clearException(env);
}

static void callRelease(JNIEnv *env, jobject camera) {
    if (gInterceptClass == nullptr || gRelease == nullptr) {
        return;
    }
    env->CallStaticVoidMethod(gInterceptClass, gRelease, camera);
    clearException(env);
}

static bool isVirtual(JNIEnv *env, jobject camera) {
    if (gInterceptClass == nullptr || camera == nullptr) {
        return false;
    }
    jmethodID method = env->GetStaticMethodID(gInterceptClass, "isVirtual", "(Ljava/lang/Object;)Z");
    if (method == nullptr) {
        clearException(env);
        return false;
    }
    jboolean value = env->CallStaticBooleanMethod(gInterceptClass, method, camera);
    clearException(env);
    return value == JNI_TRUE;
}

static jint setup36(JNIEnv *env, jobject thiz, jobject self, jint cameraId, jint rotation,
                    jboolean slowJpeg, jobject parcel, jint policy) {
    if (callInterceptSetup(env, thiz, cameraId)) {
        return 0;
    }
    return orig_setup36(env, thiz, self, cameraId, rotation, slowJpeg, parcel, policy);
}

static jint setup35(JNIEnv *env, jobject thiz, jobject self, jint cameraId, jstring packageName,
                    jint rotation, jboolean slowJpeg, jint deviceId, jint policy) {
    if (callInterceptSetup(env, thiz, cameraId)) {
        return 0;
    }
    return orig_setup35(env, thiz, self, cameraId, packageName, rotation, slowJpeg, deviceId, policy);
}

static jint setup34(JNIEnv *env, jobject thiz, jobject self, jint cameraId, jstring packageName,
                    jboolean portrait, jboolean slowJpeg) {
    if (callInterceptSetup(env, thiz, cameraId)) {
        return 0;
    }
    return orig_setup34(env, thiz, self, cameraId, packageName, portrait, slowJpeg);
}

static jint setupLegacy(JNIEnv *env, jobject thiz, jobject self, jint cameraId, jint halVersion,
                        jstring packageName, jboolean slowJpeg) {
    if (callInterceptSetup(env, thiz, cameraId)) {
        return 0;
    }
    return orig_setup_legacy(env, thiz, self, cameraId, halVersion, packageName, slowJpeg);
}

static void setPreviewTexture(JNIEnv *env, jobject thiz, jobject texture) {
    if (isVirtual(env, thiz)) {
        callPreview(env, thiz, texture, JNI_TRUE);
        return;
    }
    orig_set_preview_texture(env, thiz, texture);
}

static void setPreviewSurface(JNIEnv *env, jobject thiz, jobject surface) {
    if (isVirtual(env, thiz)) {
        callPreview(env, thiz, surface, JNI_FALSE);
        return;
    }
    orig_set_preview_surface(env, thiz, surface);
}

static void startPreview(JNIEnv *env, jobject thiz) {
    if (isVirtual(env, thiz)) {
        callStart(env, thiz);
        return;
    }
    orig_start_preview(env, thiz);
}

static void stopPreview(JNIEnv *env, jobject thiz) {
    if (isVirtual(env, thiz)) {
        callStop(env, thiz);
        return;
    }
    orig_stop_preview(env, thiz);
}

static void nativeRelease(JNIEnv *env, jobject thiz) {
    if (isVirtual(env, thiz)) {
        callRelease(env, thiz);
        return;
    }
    orig_native_release(env, thiz);
}

static jboolean previewEnabled(JNIEnv *env, jobject thiz) {
    if (isVirtual(env, thiz)) {
        return JNI_TRUE;
    }
    return orig_preview_enabled(env, thiz);
}

static jstring getParameters(JNIEnv *env, jobject thiz) {
    if (isVirtual(env, thiz) && gGetParameters != nullptr) {
        auto value = (jstring) env->CallStaticObjectMethod(gInterceptClass, gGetParameters, thiz);
        clearException(env);
        if (value != nullptr) {
            return value;
        }
    }
    return orig_get_parameters(env, thiz);
}

static void setParameters(JNIEnv *env, jobject thiz, jstring params) {
    if (isVirtual(env, thiz) && gSetParameters != nullptr) {
        env->CallStaticVoidMethod(gInterceptClass, gSetParameters, thiz, params);
        clearException(env);
        return;
    }
    orig_set_parameters(env, thiz, params);
}

static void setDisplayOrientation(JNIEnv *env, jobject thiz, jint degrees) {
    if (isVirtual(env, thiz)) {
        __android_log_print(ANDROID_LOG_INFO, VCAM_OPEN,
                            "path=Camera1.setDisplayOrientation intercepted degrees=%d", degrees);
        return;
    }
    orig_set_display_orientation(env, thiz, degrees);
}

static void autoFocus(JNIEnv *env, jobject thiz) {
    if (isVirtual(env, thiz)) {
        __android_log_print(ANDROID_LOG_INFO, VCAM_OPEN, "path=Camera1.autoFocus intercepted");
        return;
    }
    orig_auto_focus(env, thiz);
}

static void cancelAutoFocus(JNIEnv *env, jobject thiz) {
    if (isVirtual(env, thiz)) {
        return;
    }
    orig_cancel_auto_focus(env, thiz);
}

static void takePicture(JNIEnv *env, jobject thiz, jint msgType) {
    if (isVirtual(env, thiz)) {
        __android_log_print(ANDROID_LOG_INFO, VCAM_OPEN, "path=Camera1.takePicture intercepted msgType=%d", msgType);
        return;
    }
    orig_take_picture(env, thiz, msgType);
}

static void hasPreviewCallback(JNIEnv *env, jobject thiz, jboolean installed, jboolean manual) {
    if (isVirtual(env, thiz)) {
        __android_log_print(ANDROID_LOG_INFO, VCAM_OPEN,
                            "path=Camera1.setPreviewCallback intercepted installed=%d", installed);
        return;
    }
    orig_has_preview_callback(env, thiz, installed, manual);
}

static jboolean enableShutter(JNIEnv *env, jobject thiz, jboolean enabled) {
    if (isVirtual(env, thiz)) {
        return JNI_TRUE;
    }
    return orig_enable_shutter(env, thiz, enabled);
}

static void lockCamera(JNIEnv *env, jobject thiz) {
    if (isVirtual(env, thiz)) {
        return;
    }
    orig_lock(env, thiz);
}

static void unlockCamera(JNIEnv *env, jobject thiz) {
    if (isVirtual(env, thiz)) {
        return;
    }
    orig_unlock(env, thiz);
}

static void reconnectCamera(JNIEnv *env, jobject thiz) {
    if (isVirtual(env, thiz)) {
        __android_log_print(ANDROID_LOG_INFO, VCAM_OPEN, "path=Camera1.reconnect intercepted");
        return;
    }
    orig_reconnect(env, thiz);
}

struct NativeTarget {
    const char *name;
    const char *signature;
    void *replacement;
    void **original;
};

static NativeTarget kTargets[] = {
        {"native_setup", "(Ljava/lang/Object;IIZLandroid/os/Parcel;I)I", (void *) setup36, (void **) &orig_setup36},
        {"native_setup", "(Ljava/lang/Object;ILjava/lang/String;IZII)I", (void *) setup35, (void **) &orig_setup35},
        {"native_setup", "(Ljava/lang/Object;ILjava/lang/String;ZZ)I", (void *) setup34, (void **) &orig_setup34},
        {"native_setup", "(Ljava/lang/Object;IILjava/lang/String;Z)I", (void *) setupLegacy, (void **) &orig_setup_legacy},
        {"setPreviewTexture", "(Landroid/graphics/SurfaceTexture;)V", (void *) setPreviewTexture, (void **) &orig_set_preview_texture},
        {"setPreviewSurface", "(Landroid/view/Surface;)V", (void *) setPreviewSurface, (void **) &orig_set_preview_surface},
        {"startPreview", "()V", (void *) startPreview, (void **) &orig_start_preview},
        {"_stopPreview", "()V", (void *) stopPreview, (void **) &orig_stop_preview},
        {"native_release", "()V", (void *) nativeRelease, (void **) &orig_native_release},
        {"previewEnabled", "()Z", (void *) previewEnabled, (void **) &orig_preview_enabled},
        {"native_getParameters", "()Ljava/lang/String;", (void *) getParameters, (void **) &orig_get_parameters},
        {"native_setParameters", "(Ljava/lang/String;)V", (void *) setParameters, (void **) &orig_set_parameters},
        {"setDisplayOrientation", "(I)V", (void *) setDisplayOrientation, (void **) &orig_set_display_orientation},
        {"native_autoFocus", "()V", (void *) autoFocus, (void **) &orig_auto_focus},
        {"native_cancelAutoFocus", "()V", (void *) cancelAutoFocus, (void **) &orig_cancel_auto_focus},
        {"native_takePicture", "(I)V", (void *) takePicture, (void **) &orig_take_picture},
        {"setHasPreviewCallback", "(ZZ)V", (void *) hasPreviewCallback, (void **) &orig_has_preview_callback},
        {"_enableShutterSound", "(Z)Z", (void *) enableShutter, (void **) &orig_enable_shutter},
        {"lock", "()V", (void *) lockCamera, (void **) &orig_lock},
        {"unlock", "()V", (void *) unlockCamera, (void **) &orig_unlock},
        {"reconnect", "()V", (void *) reconnectCamera, (void **) &orig_reconnect},
};

static bool hookOne(JNIEnv *env, jclass cameraClass, jobject method, jclass jniHookClass, jmethodID setAccessible) {
    jclass methodClass = env->GetObjectClass(method);
    jmethodID getName = env->GetMethodID(methodClass, "getName", "()Ljava/lang/String;");
    jmethodID getModifiers = env->GetMethodID(methodClass, "getModifiers", "()I");
    jclass modifierClass = env->FindClass("java/lang/reflect/Modifier");
    jmethodID isNative = env->GetStaticMethodID(modifierClass, "isNative", "(I)Z");
    jclass utils = env->FindClass("top/niunaijun/jnihook/MethodUtils");
    jmethodID getDesc = env->GetStaticMethodID(utils, "getDesc", "(Ljava/lang/reflect/Method;)Ljava/lang/String;");
    if (getName == nullptr || getDesc == nullptr || isNative == nullptr) {
        clearException(env);
        return false;
    }
    jint modifiers = env->CallIntMethod(method, getModifiers);
    if (!env->CallStaticBooleanMethod(modifierClass, isNative, modifiers)) {
        return false;
    }
    auto name = (jstring) env->CallObjectMethod(method, getName);
    auto desc = (jstring) env->CallStaticObjectMethod(utils, getDesc, method);
    const char *nameChars = env->GetStringUTFChars(name, nullptr);
    const char *descChars = env->GetStringUTFChars(desc, nullptr);
    bool hooked = false;
    for (auto &target : kTargets) {
        if (target.original != nullptr && *target.original != nullptr) {
            continue;
        }
        if (strcmp(nameChars, target.name) == 0 && strcmp(descChars, target.signature) == 0) {
            env->CallStaticVoidMethod(jniHookClass, setAccessible, cameraClass, method);
            clearException(env);
            hooked = JniHook::HookReflected(env, cameraClass, method, nameChars, descChars,
                                            target.replacement, target.original);
            __android_log_print(hooked ? ANDROID_LOG_INFO : ANDROID_LOG_WARN, VCAM_OPEN,
                                "path=Camera1.install method=%s signature=%s hooked=%d",
                                nameChars, descChars, hooked ? 1 : 0);
            break;
        }
    }
    if (!hooked && strcmp(nameChars, "native_setup") == 0) {
        __android_log_print(ANDROID_LOG_WARN, VCAM_OPEN,
                            "path=Camera1.install native_setup_unmatched signature=%s", descChars);
    }
    env->ReleaseStringUTFChars(name, nameChars);
    env->ReleaseStringUTFChars(desc, descChars);
    return hooked;
}

void Camera1Hook::init(JNIEnv *env) {
    jclass intercept = env->FindClass("top/niunaijun/blackboxa/vcam/Camera1Intercept");
    if (intercept == nullptr) {
        clearException(env);
        __android_log_print(ANDROID_LOG_WARN, VCAM_OPEN, "path=Camera1.install intercept_class_missing");
        return;
    }
    gInterceptClass = (jclass) env->NewGlobalRef(intercept);
    gInterceptSetup = env->GetStaticMethodID(gInterceptClass, "interceptNativeSetup", "(Ljava/lang/Object;I)Z");
    gPreviewTarget = env->GetStaticMethodID(gInterceptClass, "onPreviewTarget", "(Ljava/lang/Object;Ljava/lang/Object;Z)V");
    gGetParameters = env->GetStaticMethodID(gInterceptClass, "onGetParameters", "(Ljava/lang/Object;)Ljava/lang/String;");
    gSetParameters = env->GetStaticMethodID(gInterceptClass, "onSetParameters", "(Ljava/lang/Object;Ljava/lang/String;)V");
    gStartPreview = env->GetStaticMethodID(gInterceptClass, "onStartPreview", "(Ljava/lang/Object;)V");
    gStopPreview = env->GetStaticMethodID(gInterceptClass, "onStopPreview", "(Ljava/lang/Object;)V");
    gRelease = env->GetStaticMethodID(gInterceptClass, "onRelease", "(Ljava/lang/Object;)V");
    clearException(env);

    jclass cameraClass = env->FindClass("android/hardware/Camera");
    if (cameraClass == nullptr) {
        clearException(env);
        __android_log_print(ANDROID_LOG_WARN, VCAM_OPEN, "path=Camera1.install camera_class_missing");
        return;
    }
    jclass classClass = env->FindClass("java/lang/Class");
    jmethodID getDeclaredMethods = env->GetMethodID(classClass, "getDeclaredMethods", "()[Ljava/lang/reflect/Method;");
    jclass jniHookClass = env->FindClass("top/niunaijun/jnihook/jni/JniHook");
    jmethodID setAccessible = env->GetStaticMethodID(jniHookClass, "setAccessible",
                                                     "(Ljava/lang/Class;Ljava/lang/reflect/Method;)V");
    if (getDeclaredMethods == nullptr || setAccessible == nullptr) {
        clearException(env);
        __android_log_print(ANDROID_LOG_WARN, VCAM_OPEN, "path=Camera1.install reflection_unavailable");
        return;
    }
    auto methods = (jobjectArray) env->CallObjectMethod(cameraClass, getDeclaredMethods);
    if (methods == nullptr) {
        clearException(env);
        return;
    }
    jsize count = env->GetArrayLength(methods);
    int hooked = 0;
    for (jsize i = 0; i < count; i++) {
        jobject method = env->GetObjectArrayElement(methods, i);
        if (method != nullptr && hookOne(env, cameraClass, method, jniHookClass, setAccessible)) {
            hooked++;
        }
        env->DeleteLocalRef(method);
    }
    bool setup = orig_setup36 || orig_setup35 || orig_setup34 || orig_setup_legacy;
    __android_log_print(ANDROID_LOG_INFO, VCAM_OPEN,
                        "path=Camera1.install done hooked=%d native_setup=%d texture=%d",
                        hooked, setup ? 1 : 0, orig_set_preview_texture ? 1 : 0);
}
