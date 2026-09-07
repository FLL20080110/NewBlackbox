#include <jni.h>
#include <cstdint>
#include "JniHook.h"
#include "Log.h"
#include "ArtMethod.h"

static struct {
    int api_level;
    unsigned int art_field_size;
    int art_field_flags_offset;

    unsigned int art_method_size;
    int art_method_flags_offset;
    int art_method_native_offset;

    int class_flags_offset;
    bool initialized;

    jclass method_utils_class;
    jmethodID get_method_desc_id;
    jmethodID get_method_declaring_class_id;
    jmethodID get_method_name_id;

} HookEnv;

static inline void ClearPendingException(JNIEnv *env, const char *where) {
    if (env->ExceptionCheck()) {
        ALOGE("JniHook: pending JNI exception at %s", where);
        env->ExceptionClear();
    }
}

static const char *GetMethodDesc(JNIEnv *env, jobject javaMethod) {
    if (!HookEnv.method_utils_class || !HookEnv.get_method_desc_id) return nullptr;
    auto desc = reinterpret_cast<jstring>(env->CallStaticObjectMethod(HookEnv.method_utils_class,
                                                                      HookEnv.get_method_desc_id,
                                                                      javaMethod));
    if (env->ExceptionCheck() || !desc) {
        ClearPendingException(env, "GetMethodDesc");
        return nullptr;
    }
    return env->GetStringUTFChars(desc, JNI_FALSE);
}

static const char *GetMethodDeclaringClass(JNIEnv *env, jobject javaMethod) {
    if (!HookEnv.method_utils_class || !HookEnv.get_method_declaring_class_id) return nullptr;
    auto desc = reinterpret_cast<jstring>(env->CallStaticObjectMethod(HookEnv.method_utils_class,
                                                                      HookEnv.get_method_declaring_class_id,
                                                                      javaMethod));
    if (env->ExceptionCheck() || !desc) {
        ClearPendingException(env, "GetMethodDeclaringClass");
        return nullptr;
    }
    return env->GetStringUTFChars(desc, JNI_FALSE);
}

static const char *GetMethodName(JNIEnv *env, jobject javaMethod) {
    if (!HookEnv.method_utils_class || !HookEnv.get_method_name_id) return nullptr;
    auto desc = reinterpret_cast<jstring>(env->CallStaticObjectMethod(HookEnv.method_utils_class,
                                                                      HookEnv.get_method_name_id,
                                                                      javaMethod));
    if (env->ExceptionCheck() || !desc) {
        ClearPendingException(env, "GetMethodName");
        return nullptr;
    }
    return env->GetStringUTFChars(desc, JNI_FALSE);
}

inline static uint32_t GetAccessFlags(const char *art_method) {
    if (!HookEnv.initialized || !art_method || HookEnv.art_method_flags_offset <= 0 ||
        static_cast<unsigned int>(HookEnv.art_method_flags_offset + sizeof(uint32_t)) > HookEnv.art_method_size) {
        return 0;
    }
    return *reinterpret_cast<const uint32_t *>(art_method + HookEnv.art_method_flags_offset);
}

inline static bool SetAccessFlags(char *art_method, uint32_t flags) {
    if (!HookEnv.initialized || !art_method || HookEnv.art_method_flags_offset <= 0 ||
        static_cast<unsigned int>(HookEnv.art_method_flags_offset + sizeof(uint32_t)) > HookEnv.art_method_size) {
        return false;
    }
    *reinterpret_cast<uint32_t *>(art_method + HookEnv.art_method_flags_offset) = flags;
    return true;
}

inline static bool AddAccessFlag(char *art_method, uint32_t flag) {
    uint32_t old_flag = GetAccessFlags(art_method);
    uint32_t new_flag = old_flag | flag;
    return new_flag != old_flag && SetAccessFlags(art_method, new_flag);
}

inline static bool ClearAccessFlag(char *art_method, uint32_t flag) {
    uint32_t old_flag = GetAccessFlags(art_method);
    uint32_t new_flag = old_flag & ~flag;
    return new_flag != old_flag && SetAccessFlags(art_method, new_flag);
}

inline static bool HasAccessFlag(char *art_method, uint32_t flag) {
    uint32_t flags = GetAccessFlags(art_method);
    return flags != 0 && (flags & flag) == flag;
}

inline static bool ClearFastNativeFlag(char *art_method) {
    return HookEnv.api_level < __ANDROID_API_P__ && ClearAccessFlag(art_method, kAccFastNative);
}

static void *GetArtMethod(JNIEnv *env, jclass clazz, jmethodID methodId) {
    if (!clazz || !methodId) return nullptr;
    if (HookEnv.api_level >= __ANDROID_API_Q__) {
        jclass executable = env->FindClass("java/lang/reflect/Executable");
        if (!executable) {
            ClearPendingException(env, "FindClass Executable");
            return nullptr;
        }
        jfieldID artId = env->GetFieldID(executable, "artMethod", "J");
        if (!artId) {
            ClearPendingException(env, "GetFieldID Executable.artMethod");
            return nullptr;
        }
        jobject method = env->ToReflectedMethod(clazz, methodId, true);
        if (!method) {
            ClearPendingException(env, "ToReflectedMethod");
            return nullptr;
        }
        jlong value = env->GetLongField(method, artId);
        if (env->ExceptionCheck()) {
            ClearPendingException(env, "GetLongField artMethod");
            return nullptr;
        }
        return reinterpret_cast<void *>(static_cast<uintptr_t>(value));
    }
    return methodId;
}

static void *GetFieldMethod(JNIEnv *env, jobject field) {
    if (!field) return nullptr;
    if (HookEnv.api_level >= __ANDROID_API_Q__) {
        jclass fieldClass = env->FindClass("java/lang/reflect/Field");
        if (!fieldClass) {
            ClearPendingException(env, "FindClass Field");
            return nullptr;
        }
        jmethodID getArtField = env->GetMethodID(fieldClass, "getArtField", "()J");
        if (!getArtField) {
            ClearPendingException(env, "GetMethodID Field.getArtField");
            return nullptr;
        }
        jlong value = env->CallLongMethod(field, getArtField);
        if (env->ExceptionCheck()) {
            ClearPendingException(env, "CallLongMethod getArtField");
            return nullptr;
        }
        return reinterpret_cast<void *>(static_cast<uintptr_t>(value));
    }
    return env->FromReflectedField(field);
}

bool CheckFlags(void *artMethod) {
    if (!HookEnv.initialized || !artMethod) return false;
    char *method = static_cast<char *>(artMethod);
    if (!HasAccessFlag(method, kAccNative)) {
        ALOGD("JniHook: method is not native, skipping hook");
        return false;
    }
    ClearFastNativeFlag(method);
    return true;
}

void JniHook::HookJniFun(JNIEnv *env, jobject java_method, void *new_fun,
                         void **orig_fun, bool is_static) {
    if (!HookEnv.initialized || !java_method || !new_fun || !orig_fun) return;
    const char *class_name = GetMethodDeclaringClass(env, java_method);
    const char *method_name = GetMethodName(env, java_method);
    const char *sign = GetMethodDesc(env, java_method);
    if (!class_name || !method_name || !sign) return;
    HookJniFun(env, class_name, method_name, sign, new_fun, orig_fun, is_static);
}

void JniHook::HookJniFun(JNIEnv *env, const char *class_name, const char *method_name, const char *sign,
                         void *new_fun, void **orig_fun, bool is_static) {
    if (!HookEnv.initialized || !class_name || !method_name || !sign || !new_fun || !orig_fun) {
        return;
    }

    const size_t nativeIndex = static_cast<size_t>(HookEnv.art_method_native_offset);
    const size_t pointerCount = HookEnv.art_method_size / sizeof(uintptr_t);
    if (nativeIndex == 0 || nativeIndex >= pointerCount) {
        ALOGE("JniHook: invalid native offset %zu (method size %u)", nativeIndex, HookEnv.art_method_size);
        return;
    }

    if (env->ExceptionCheck()) env->ExceptionClear();
    jclass clazz = env->FindClass(class_name);
    if (!clazz) {
        ALOGD("findClass fail: %s %s", class_name, method_name);
        env->ExceptionClear();
        return;
    }

    jmethodID method = is_static ? env->GetStaticMethodID(clazz, method_name, sign)
                                 : env->GetMethodID(clazz, method_name, sign);
    if (!method) {
        env->ExceptionClear();
        ALOGD("get method id fail: %s %s", class_name, method_name);
        return;
    }

    auto artMethod = reinterpret_cast<uintptr_t *>(GetArtMethod(env, clazz, method));
    if (!artMethod || !CheckFlags(artMethod)) {
        ALOGD("JniHook: unsafe/non-native method, skip %s.%s", class_name, method_name);
        return;
    }

    uintptr_t candidate = artMethod[nativeIndex];
    // Native code pointers on Android userspace must not be tiny values.  The
    // observed Android 16 crash jumped to 0x88c; reject this class of corrupt
    // inferred entry before installing the replacement.
    if (candidate < 0x10000u) {
        ALOGE("JniHook: rejected implausible original native pointer %p for %s.%s",
              reinterpret_cast<void *>(candidate), class_name, method_name);
        return;
    }

    JNINativeMethod gMethods[] = {
            {method_name, sign, new_fun},
    };

    if (env->RegisterNatives(clazz, gMethods, 1) < 0 || env->ExceptionCheck()) {
        ALOGE("jni hook error. class: %s, method: %s", class_name, method_name);
        env->ExceptionClear();
        return;
    }

    *orig_fun = reinterpret_cast<void *>(candidate);

    if (HookEnv.api_level == __ANDROID_API_O__ || HookEnv.api_level == __ANDROID_API_O_MR1__) {
        AddAccessFlag(reinterpret_cast<char *>(artMethod), kAccFastNative);
    }
    ALOGD("register class: %s, method: %s success", class_name, method_name);
}

__attribute__((section (".mytext"))) JNICALL void native_offset(JNIEnv *env, jclass obj) {}
__attribute__((section (".mytext"))) JNICALL void native_offset2(JNIEnv *env, jclass obj) {}

__attribute__((section (".mytext"))) JNICALL void set_method_accessible
        (JNIEnv *env, jclass obj, jclass clazz, jobject method) {
    if (!HookEnv.initialized || !clazz || !method) return;
    jmethodID methodId = env->FromReflectedMethod(method);
    char *art_method = static_cast<char *>(GetArtMethod(env, clazz, methodId));
    if (!art_method) return;
    AddAccessFlag(art_method, kAccPublic);
    if (HookEnv.api_level >= __ANDROID_API_Q__) AddAccessFlag(art_method, kAccPublicApi);
}

__attribute__((section (".mytext"))) JNICALL void set_field_accessible
        (JNIEnv *env, jclass obj, jclass clazz, jobject field) {
    if (!HookEnv.initialized || !field || HookEnv.art_field_flags_offset <= 0) return;
    char *artField = static_cast<char *>(GetFieldMethod(env, field));
    if (!artField) return;
    AddAccessFlag(artField, kAccPublic);
    if (HookEnv.api_level >= __ANDROID_API_Q__) AddAccessFlag(artField, kAccPublicApi);
    ClearAccessFlag(artField, kAccFinal);
}

void registerNative(JNIEnv *env) {
    jclass clazz = env->FindClass("top/niunaijun/jnihook/jni/JniHook");
    if (!clazz) {
        ClearPendingException(env, "registerNative FindClass");
        return;
    }
    JNINativeMethod gMethods[] = {
            {"nativeOffset",  "()V",                                            (void *) native_offset},
            {"nativeOffset2", "()V",                                            (void *) native_offset2},
            {"setAccessible", "(Ljava/lang/Class;Ljava/lang/reflect/Method;)V", (void *) set_method_accessible},
            {"setAccessible", "(Ljava/lang/Class;Ljava/lang/reflect/Field;)V",  (void *) set_field_accessible},
    };
    if (env->RegisterNatives(clazz, gMethods, sizeof(gMethods) / sizeof(gMethods[0])) < 0) {
        ALOGE("jni register error");
        env->ExceptionClear();
    }
}

void JniHook::InitJniHook(JNIEnv *env, int api_level) {
    HookEnv = {};
    HookEnv.api_level = api_level;
    registerNative(env);

    jclass clazz = env->FindClass("top/niunaijun/jnihook/jni/JniHook");
    if (!clazz) {
        ClearPendingException(env, "Init FindClass JniHook");
        return;
    }

    jmethodID nativeOffsetId = env->GetStaticMethodID(clazz, "nativeOffset", "()V");
    jmethodID nativeOffset2Id = env->GetStaticMethodID(clazz, "nativeOffset2", "()V");
    jfieldID nativeOffsetFieldId = env->GetStaticFieldID(clazz, "NATIVE_OFFSET", "I");
    jfieldID nativeOffsetField2Id = env->GetStaticFieldID(clazz, "NATIVE_OFFSET_2", "I");
    if (!nativeOffsetId || !nativeOffset2Id || !nativeOffsetFieldId || !nativeOffsetField2Id) {
        ClearPendingException(env, "Init lookup probe members");
        return;
    }

    void *nativeOffsetField = GetFieldMethod(env, env->ToReflectedField(clazz, nativeOffsetFieldId, true));
    void *nativeOffsetField2 = GetFieldMethod(env, env->ToReflectedField(clazz, nativeOffsetField2Id, true));
    void *nativeOffset = GetArtMethod(env, clazz, nativeOffsetId);
    void *nativeOffset2 = GetArtMethod(env, clazz, nativeOffset2Id);
    if (!nativeOffsetField || !nativeOffsetField2 || !nativeOffset || !nativeOffset2) {
        ALOGE("JniHook: unable to resolve ART probe addresses on API %d", api_level);
        return;
    }

    uintptr_t field1 = reinterpret_cast<uintptr_t>(nativeOffsetField);
    uintptr_t field2 = reinterpret_cast<uintptr_t>(nativeOffsetField2);
    uintptr_t method1 = reinterpret_cast<uintptr_t>(nativeOffset);
    uintptr_t method2 = reinterpret_cast<uintptr_t>(nativeOffset2);
    if (field2 <= field1 || method2 <= method1) {
        ALOGE("JniHook: non-monotonic ART probe addresses, disabling hooks");
        return;
    }

    HookEnv.art_field_size = static_cast<unsigned int>(field2 - field1);
    HookEnv.art_method_size = static_cast<unsigned int>(method2 - method1);

    // ART structures are small. Reject absurd differences before any scan so
    // a changed private layout cannot turn the probe into an out-of-bounds walk.
    if (HookEnv.art_method_size < 16 || HookEnv.art_method_size > 512 ||
        HookEnv.art_field_size < 4 || HookEnv.art_field_size > 256) {
        ALOGE("JniHook: implausible ART sizes method=%u field=%u; disabling hooks",
              HookEnv.art_method_size, HookEnv.art_field_size);
        return;
    }

    auto artMethod = reinterpret_cast<uintptr_t *>(nativeOffset);
    const size_t pointerCount = HookEnv.art_method_size / sizeof(uintptr_t);
    for (size_t i = 0; i < pointerCount; ++i) {
        if (reinterpret_cast<void *>(artMethod[i]) == reinterpret_cast<void *>(native_offset)) {
            HookEnv.art_method_native_offset = static_cast<int>(i);
            break;
        }
    }
    if (HookEnv.art_method_native_offset <= 0) {
        ALOGE("JniHook: art_method_native_offset not found");
        return;
    }

    uint32_t flags = kAccPublic | kAccStatic | kAccNative | kAccFinal;
    if (api_level >= __ANDROID_API_Q__) flags |= kAccPublicApi;
    uint32_t flagsWithNterp = flags;
    if (api_level >= __ANDROID_API_S__) flagsWithNterp |= kAccNterpInvokeFastPathFlag;

    char *start = reinterpret_cast<char *>(nativeOffset);
    const size_t wordCount = HookEnv.art_method_size / sizeof(uint32_t);
    for (size_t i = 1; i < wordCount; ++i) {
        uint32_t value = *reinterpret_cast<uint32_t *>(start + i * sizeof(uint32_t));
        if (value == flags || value == flagsWithNterp) {
            HookEnv.art_method_flags_offset = static_cast<int>(i * sizeof(uint32_t));
            break;
        }
    }
    if (HookEnv.art_method_flags_offset <= 0) {
        ALOGE("JniHook: art_method_flags_offset not found");
        return;
    }

    uint32_t fieldFlags = kAccPublic | kAccStatic | kAccFinal;
    if (api_level >= __ANDROID_API_Q__) fieldFlags |= kAccPublicApi;
    char *fieldStart = reinterpret_cast<char *>(nativeOffsetField);
    const size_t fieldWordCount = HookEnv.art_field_size / sizeof(uint32_t);
    for (size_t i = 1; i < fieldWordCount; ++i) {
        uint32_t value = *reinterpret_cast<uint32_t *>(fieldStart + i * sizeof(uint32_t));
        if (value == fieldFlags) {
            HookEnv.art_field_flags_offset = static_cast<int>(i * sizeof(uint32_t));
            break;
        }
    }
    if (HookEnv.art_field_flags_offset <= 0) {
        ALOGE("JniHook: art_field_flags_offset not found");
        return;
    }

    HookEnv.method_utils_class = reinterpret_cast<jclass>(env->NewGlobalRef(
            env->FindClass("top/niunaijun/jnihook/MethodUtils")));
    if (!HookEnv.method_utils_class) {
        ClearPendingException(env, "Init MethodUtils");
        return;
    }
    HookEnv.get_method_desc_id = env->GetStaticMethodID(HookEnv.method_utils_class, "getDesc",
                                                        "(Ljava/lang/reflect/Method;)Ljava/lang/String;");
    HookEnv.get_method_declaring_class_id = env->GetStaticMethodID(HookEnv.method_utils_class,
                                                                   "getDeclaringClass",
                                                                   "(Ljava/lang/reflect/Method;)Ljava/lang/String;");
    HookEnv.get_method_name_id = env->GetStaticMethodID(HookEnv.method_utils_class, "getMethodName",
                                                        "(Ljava/lang/reflect/Method;)Ljava/lang/String;");
    if (!HookEnv.get_method_desc_id || !HookEnv.get_method_declaring_class_id || !HookEnv.get_method_name_id) {
        ClearPendingException(env, "Init MethodUtils methods");
        return;
    }

    HookEnv.initialized = true;
    ALOGD("JniHook: ART probe ready on API %d, methodSize=%u nativeIndex=%d flagsOffset=%d",
          api_level, HookEnv.art_method_size, HookEnv.art_method_native_offset,
          HookEnv.art_method_flags_offset);
}
