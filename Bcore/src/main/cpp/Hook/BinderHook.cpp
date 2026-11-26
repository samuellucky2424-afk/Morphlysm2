



#include "BinderHook.h"
#include <IO.h>
#include <BoxCore.h>
#include "UnixFileSystemHook.h"
#import "JniHook/JniHook.h"



HOOK_JNI(jint, getCallingUid, JNIEnv *env, jobject obj) {
    int orig = orig_getCallingUid(env, obj);
    return BoxCore::getCallingUid(env, orig);
}

static jclass gPackageBinderClass = nullptr;
static jmethodID gRewriteIfGoogle = nullptr;

HOOK_JNI(jboolean, binderProxyTransact, JNIEnv *env, jobject thiz, jint code, jobject data,
         jobject reply, jint flags) {
    static thread_local int depth = 0;
    if (orig_binderProxyTransact == nullptr) {
        return JNI_FALSE;
    }
    if (depth > 0 || gRewriteIfGoogle == nullptr || data == nullptr) {
        return orig_binderProxyTransact(env, thiz, code, data, reply, flags);
    }
    depth++;
    jobject rewritten = env->CallStaticObjectMethod(gPackageBinderClass, gRewriteIfGoogle, thiz, data);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        rewritten = nullptr;
    }
    depth--;
    jboolean result = orig_binderProxyTransact(env, thiz, code,
                                               rewritten != nullptr ? rewritten : data, reply, flags);
    if (rewritten != nullptr) {
        jclass parcelClass = env->GetObjectClass(rewritten);
        jmethodID recycle = env->GetMethodID(parcelClass, "recycle", "()V");
        if (recycle != nullptr) {
            env->CallVoidMethod(rewritten, recycle);
        }
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        env->DeleteLocalRef(parcelClass);
        env->DeleteLocalRef(rewritten);
    }
    return result;
}

void BinderHook::init(JNIEnv *env) {
    const char *clazz = "android/os/Binder";
    JniHook::HookJniFun(env, clazz, "getCallingUid", "()I", (void *) new_getCallingUid,
                        (void **) (&orig_getCallingUid), true);

    jclass rewriteClass = env->FindClass("top/niunaijun/blackbox/fake/delegate/GooglePackageBinder");
    if (rewriteClass != nullptr) {
        gPackageBinderClass = (jclass) env->NewGlobalRef(rewriteClass);
        gRewriteIfGoogle = env->GetStaticMethodID(gPackageBinderClass, "rewriteIfGoogle",
                                                   "(Landroid/os/IBinder;Landroid/os/Parcel;)Landroid/os/Parcel;");
        env->DeleteLocalRef(rewriteClass);
    }
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        gRewriteIfGoogle = nullptr;
    }
    orig_binderProxyTransact = nullptr;
    JniHook::HookJniFun(env, "android/os/BinderProxy", "transact",
                        "(ILandroid/os/Parcel;Landroid/os/Parcel;I)Z",
                        (void *) new_binderProxyTransact,
                        (void **) (&orig_binderProxyTransact), false);
}