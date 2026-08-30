#include <windows.h>
#include <jni.h>
#include <jvmti.h>
#include <cstring>
#include <cwchar>
#include <string>

#ifndef INJECTOR_PAYLOAD_PREFIX
#define INJECTOR_PAYLOAD_PREFIX L"playeresp_payload"
#endif
#ifndef INJECTOR_BOOTSTRAP_CLASS
#define INJECTOR_BOOTSTRAP_CLASS "playeresp.inject.Bootstrap"
#endif

using GetCreatedJavaVMs = jint(JNICALL*)(JavaVM**, jsize, jsize*);
static HMODULE selfModule;
static jclass callbackClass;
static jmethodID clientTickCallback;
static jmethodID worldRenderCallback;
static jmethodID hudRenderCallback;
static jmethodID clientTickTarget;
static jmethodID worldRenderTarget;
static jmethodID hudRenderTarget;
static bool worldRenderTargetHasPass;
static bool callbacksInstalled;

struct BootstrapReport {
    DWORD stage;
    wchar_t message[512];
};

enum class RuntimeKind { NONE, NAMED, SRG, OBFUSCATED };

struct RuntimeProbe {
    bool jvmtiAvailable = false;
    bool classesAvailable = false;
    bool namedClassFound = false;
    bool obfuscatedClassFound = false;
    jint loadedClassCount = 0;
};

static bool clearException(JNIEnv* env) {
    if (!env->ExceptionCheck()) return false;
    env->ExceptionClear();
    return true;
}

static void setReport(BootstrapReport* report, DWORD stage, const wchar_t* message) {
    if (!report) return;
    report->stage = stage;
    std::wcsncpy(report->message, message ? message : L"", 511);
    report->message[511] = L'\0';
}

static void appendJavaString(JNIEnv* env, jstring text, wchar_t* destination, size_t capacity) {
    if (!text || !destination || capacity < 2) return;
    const jchar* characters = env->GetStringChars(text, nullptr);
    if (!characters) return;
    size_t used = std::wcslen(destination);
    jsize length = env->GetStringLength(text);
    size_t available = capacity - used - 1;
    if (static_cast<size_t>(length) > available) length = static_cast<jsize>(available);
    std::memcpy(destination + used, characters, static_cast<size_t>(length) * sizeof(jchar));
    destination[used + static_cast<size_t>(length)] = L'\0';
    env->ReleaseStringChars(text, characters);
}

static void captureException(JNIEnv* env, BootstrapReport* report, DWORD stage) {
    jthrowable exception = env->ExceptionOccurred();
    env->ExceptionClear();
    if (!report) return;
    report->stage = stage;
    report->message[0] = L'\0';
    if (!exception) return;
    jclass throwable = env->FindClass("java/lang/Throwable");
    jmethodID toString = throwable ? env->GetMethodID(throwable, "toString", "()Ljava/lang/String;") : nullptr;
    jstring text = toString ? static_cast<jstring>(env->CallObjectMethod(exception, toString)) : nullptr;
    if (env->ExceptionCheck()) { env->ExceptionClear(); return; }
    appendJavaString(env, text, report->message, 512);
    jmethodID getStackTrace = throwable
        ? env->GetMethodID(throwable, "getStackTrace", "()[Ljava/lang/StackTraceElement;") : nullptr;
    jobjectArray trace = getStackTrace
        ? static_cast<jobjectArray>(env->CallObjectMethod(exception, getStackTrace)) : nullptr;
    if (env->ExceptionCheck()) { env->ExceptionClear(); return; }
    if (!trace || env->GetArrayLength(trace) == 0) return;
    jobject first = env->GetObjectArrayElement(trace, 0);
    jclass frameClass = first ? env->GetObjectClass(first) : nullptr;
    jmethodID frameToString = frameClass
        ? env->GetMethodID(frameClass, "toString", "()Ljava/lang/String;") : nullptr;
    jstring frameText = frameToString
        ? static_cast<jstring>(env->CallObjectMethod(first, frameToString)) : nullptr;
    if (env->ExceptionCheck()) { env->ExceptionClear(); return; }
    if (frameText && std::wcslen(report->message) < 508) std::wcscat(report->message, L" @ ");
    appendJavaString(env, frameText, report->message, 512);
}

static jobject tryStaticGetter(JNIEnv* env, jclass type, const char* name, const char* descriptor) {
    jmethodID getter = env->GetStaticMethodID(type, name, descriptor);
    if (clearException(env) || !getter) return nullptr;
    jobject value = env->CallStaticObjectMethod(type, getter);
    if (clearException(env)) return nullptr;
    return value;
}

static jobject tryStaticField(JNIEnv* env, jclass type, const char* name, const char* descriptor) {
    jfieldID field = env->GetStaticFieldID(type, name, descriptor);
    if (clearException(env) || !field) return nullptr;
    jobject value = env->GetStaticObjectField(type, field);
    if (clearException(env)) return nullptr;
    return value;
}

static jobject findSelfTypedStaticField(JNIEnv* env, jvmtiEnv* jvmti, jclass type, const char* classSignature) {
    jint count = 0;
    jfieldID* fields = nullptr;
    if (jvmti->GetClassFields(type, &count, &fields) != JVMTI_ERROR_NONE || !fields) return nullptr;
    jobject result = nullptr;
    for (jint i = 0; i < count && !result; i++) {
        char* name = nullptr;
        char* signature = nullptr;
        char* generic = nullptr;
        jint modifiers = 0;
        const bool metadata = jvmti->GetFieldName(type, fields[i], &name, &signature, &generic) == JVMTI_ERROR_NONE;
        const bool isStatic = jvmti->GetFieldModifiers(type, fields[i], &modifiers) == JVMTI_ERROR_NONE
            && (modifiers & 0x0008) != 0;
        if (metadata && isStatic && signature && std::strcmp(signature, classSignature) == 0) {
            result = env->GetStaticObjectField(type, fields[i]);
            if (clearException(env)) result = nullptr;
        }
        if (name) jvmti->Deallocate(reinterpret_cast<unsigned char*>(name));
        if (signature) jvmti->Deallocate(reinterpret_cast<unsigned char*>(signature));
        if (generic) jvmti->Deallocate(reinterpret_cast<unsigned char*>(generic));
    }
    jvmti->Deallocate(reinterpret_cast<unsigned char*>(fields));
    return result;
}

static jobject findMinecraftInstance(JNIEnv* env, jvmtiEnv* jvmti, jclass type, bool namedClass,
    RuntimeKind* resolvedKind) {
    const char* classSignature = namedClass ? "Lnet/minecraft/client/Minecraft;" : "Lave;";
    const char* descriptor = namedClass ? "()Lnet/minecraft/client/Minecraft;" : "()Lave;";
    if (!namedClass) {
        jobject value = tryStaticGetter(env, type, "A", descriptor);
        if (!value) value = tryStaticField(env, type, "S", classSignature);
        if (!value) value = findSelfTypedStaticField(env, jvmti, type, classSignature);
        if (value) *resolvedKind = RuntimeKind::OBFUSCATED;
        return value;
    }
    jobject value = tryStaticGetter(env, type, "getMinecraft", descriptor);
    if (!value) value = tryStaticField(env, type, "theMinecraft", classSignature);
    if (value) {
        *resolvedKind = RuntimeKind::NAMED;
        return value;
    }
    value = tryStaticGetter(env, type, "func_71410_x", descriptor);
    if (!value) value = tryStaticField(env, type, "field_71432_P", classSignature);
    if (!value) value = tryStaticGetter(env, type, "A", descriptor);
    if (!value) value = tryStaticField(env, type, "S", classSignature);
    if (!value) value = findSelfTypedStaticField(env, jvmti, type, classSignature);
    if (value) *resolvedKind = RuntimeKind::SRG;
    return value;
}

static RuntimeKind findMinecraftRuntime(JNIEnv* env, JavaVM* vm, jclass* minecraftClass, jobject* instance,
    RuntimeProbe* probe) {
    *minecraftClass = nullptr;
    *instance = nullptr;
    jvmtiEnv* jvmti = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&jvmti), JVMTI_VERSION_1_2) != JNI_OK || !jvmti)
        return RuntimeKind::NONE;
    if (probe) probe->jvmtiAvailable = true;
    jint count = 0;
    jclass* classes = nullptr;
    if (jvmti->GetLoadedClasses(&count, &classes) != JVMTI_ERROR_NONE || !classes)
        return RuntimeKind::NONE;
    if (probe) {
        probe->classesAvailable = true;
        probe->loadedClassCount = count;
    }
    RuntimeKind result = RuntimeKind::NONE;
    for (jint i = 0; i < count; i++) {
        char* signature = nullptr;
        char* generic = nullptr;
        if (result == RuntimeKind::NONE
            && jvmti->GetClassSignature(classes[i], &signature, &generic) == JVMTI_ERROR_NONE && signature) {
            const bool srg = std::strcmp(signature, "Lnet/minecraft/client/Minecraft;") == 0;
            const bool obfuscated = std::strcmp(signature, "Lave;") == 0;
            if (srg || obfuscated) {
                if (probe) {
                    probe->namedClassFound = probe->namedClassFound || srg;
                    probe->obfuscatedClassFound = probe->obfuscatedClassFound || obfuscated;
                }
                RuntimeKind resolvedKind = RuntimeKind::NONE;
                jobject candidate = findMinecraftInstance(env, jvmti, classes[i], srg, &resolvedKind);
                if (candidate) {
                    *minecraftClass = static_cast<jclass>(env->NewLocalRef(classes[i]));
                    *instance = candidate;
                    result = resolvedKind;
                }
                clearException(env);
            }
        }
        if (signature) jvmti->Deallocate(reinterpret_cast<unsigned char*>(signature));
        if (generic) jvmti->Deallocate(reinterpret_cast<unsigned char*>(generic));
        env->DeleteLocalRef(classes[i]);
    }
    jvmti->Deallocate(reinterpret_cast<unsigned char*>(classes));
    return result;
}

static jclass findLoadedClass(JNIEnv* env, JavaVM* vm, const char* wantedSignature) {
    jvmtiEnv* jvmti = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&jvmti), JVMTI_VERSION_1_2) != JNI_OK || !jvmti) return nullptr;
    jint count = 0;
    jclass* classes = nullptr;
    if (jvmti->GetLoadedClasses(&count, &classes) != JVMTI_ERROR_NONE || !classes) return nullptr;
    jclass result = nullptr;
    for (jint i = 0; i < count; i++) {
        char* signature = nullptr;
        char* generic = nullptr;
        if (!result && jvmti->GetClassSignature(classes[i], &signature, &generic) == JVMTI_ERROR_NONE
            && signature && std::strcmp(signature, wantedSignature) == 0) {
            result = static_cast<jclass>(env->NewLocalRef(classes[i]));
        }
        if (signature) jvmti->Deallocate(reinterpret_cast<unsigned char*>(signature));
        if (generic) jvmti->Deallocate(reinterpret_cast<unsigned char*>(generic));
        env->DeleteLocalRef(classes[i]);
    }
    jvmti->Deallocate(reinterpret_cast<unsigned char*>(classes));
    return result;
}

static jmethodID findMethod(JNIEnv* env, jclass type, const char* descriptor,
    const char* first, const char* second, const char* third) {
    const char* names[] = {first, second, third};
    for (const char* name : names) {
        if (!name) continue;
        jmethodID method = env->GetMethodID(type, name, descriptor);
        if (clearException(env)) method = nullptr;
        if (method) return method;
    }
    return nullptr;
}

static void invokeJavaCallback(JNIEnv* env, jmethodID method) {
    if (!callbackClass || !method) return;
    env->CallStaticVoidMethod(callbackClass, method);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
}

static void JNICALL onBreakpoint(jvmtiEnv* jvmti, JNIEnv* env, jthread thread, jmethodID method, jlocation location) {
    if (!callbackClass) return;
    if (method == clientTickTarget) invokeJavaCallback(env, clientTickCallback);
    else if (method == worldRenderTarget) {
        jint pass = 0;
        if (worldRenderTargetHasPass
            && jvmti->GetLocalInt(thread, 0, 1, &pass) != JVMTI_ERROR_NONE) return;
        env->CallStaticVoidMethod(callbackClass, worldRenderCallback, pass);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
    } else if (method == hudRenderTarget) {
        jfloat partialTicks = 0.0F;
        if (jvmti->GetLocalFloat(thread, 0, 1, &partialTicks) != JVMTI_ERROR_NONE) return;
        env->CallStaticVoidMethod(callbackClass, hudRenderCallback, partialTicks);
        if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
    }
}

static bool startJavaFallback(JNIEnv* env, jclass bootstrapClass, BootstrapReport* report) {
    jmethodID startFallback = env->GetStaticMethodID(bootstrapClass, "startFallback", "()V");
    if (clearException(env) || !startFallback) {
        setReport(report, 13, L"PlayerESP Java fallback method was not found. Rebuild the named payload JAR.");
        return false;
    }
    env->CallStaticVoidMethod(bootstrapClass, startFallback);
    if (env->ExceptionCheck()) {
        captureException(env, report, 13);
        return false;
    }
    callbacksInstalled = true;
    return true;
}

static bool installRuntimeCallbacks(JNIEnv* env, JavaVM* vm, jclass minecraftClass,
    jclass bootstrapClass, RuntimeKind kind, BootstrapReport* report) {
    if (callbacksInstalled) return true;
    jvmtiEnv* jvmti = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&jvmti), JVMTI_VERSION_1_2) != JNI_OK || !jvmti) {
        setReport(report, 13, L"JVMTI is unavailable while installing runtime callbacks.");
        return false;
    }
    jvmtiCapabilities potential{};
    jvmtiError error = jvmti->GetPotentialCapabilities(&potential);
    if (error != JVMTI_ERROR_NONE) {
        wchar_t message[160]{};
        swprintf(message, 160, L"JVMTI GetPotentialCapabilities failed with error %d.",
            static_cast<int>(error));
        setReport(report, 13, message);
        return false;
    }
    const bool useBreakpoints = potential.can_generate_breakpoint_events != 0
        && potential.can_access_local_variables != 0;
    if (!useBreakpoints) return startJavaFallback(env, bootstrapClass, report);
    jvmtiCapabilities capabilities{};
    capabilities.can_generate_breakpoint_events = 1;
    capabilities.can_access_local_variables = 1;
    error = jvmti->AddCapabilities(&capabilities);
    if (error == JVMTI_ERROR_NOT_AVAILABLE) return startJavaFallback(env, bootstrapClass, report);
    if (error != JVMTI_ERROR_NONE) {
        wchar_t message[160]{};
        swprintf(message, 160, L"JVMTI breakpoint capability request failed with error %d.",
            static_cast<int>(error));
        setReport(report, 13, message);
        return false;
    }

    if (kind == RuntimeKind::NAMED)
        clientTickTarget = findMethod(env, minecraftClass, "()V", "runTick", "func_71407_l", "s");
    else if (kind == RuntimeKind::SRG)
        clientTickTarget = findMethod(env, minecraftClass, "()V", "func_71407_l", "runTick", "s");
    else
        clientTickTarget = findMethod(env, minecraftClass, "()V", "s", "func_71407_l", "runTick");
    const char* rendererSignature = kind == RuntimeKind::OBFUSCATED
        ? "Lbfk;" : "Lnet/minecraft/client/renderer/EntityRenderer;";
    jclass rendererClass = findLoadedClass(env, vm, rendererSignature);
    if (rendererClass) {
        if (kind == RuntimeKind::NAMED)
            worldRenderTarget = findMethod(env, rendererClass, "(IFJ)V", "renderWorldPass", "func_175068_a", "a");
        else if (kind == RuntimeKind::SRG)
            worldRenderTarget = findMethod(env, rendererClass, "(IFJ)V", "func_175068_a", "renderWorldPass", "a");
        else
            worldRenderTarget = findMethod(env, rendererClass, "(IFJ)V", "a", "func_175068_a", "renderWorldPass");
        worldRenderTargetHasPass = worldRenderTarget != nullptr;
        if (!worldRenderTarget) {
            if (kind == RuntimeKind::NAMED)
                worldRenderTarget = findMethod(env, rendererClass, "(FJ)V", "renderWorld", "func_78471_a", "b");
            else if (kind == RuntimeKind::SRG)
                worldRenderTarget = findMethod(env, rendererClass, "(FJ)V", "func_78471_a", "renderWorld", "b");
            else
                worldRenderTarget = findMethod(env, rendererClass, "(FJ)V", "b", "func_78471_a", "renderWorld");
        }
        env->DeleteLocalRef(rendererClass);
    }
    jmethodID getHudClass = env->GetStaticMethodID(bootstrapClass, "getHudClass", "()Ljava/lang/Class;");
    if (!clearException(env) && getHudClass) {
        jobject hudClassObject = env->CallStaticObjectMethod(bootstrapClass, getHudClass);
        if (!clearException(env) && hudClassObject) {
            jclass hudClass = static_cast<jclass>(hudClassObject);
            if (kind == RuntimeKind::NAMED)
                hudRenderTarget = findMethod(env, hudClass, "(F)V", "renderGameOverlay", "func_175180_a", "a");
            else if (kind == RuntimeKind::SRG)
                hudRenderTarget = findMethod(env, hudClass, "(F)V", "func_175180_a", "renderGameOverlay", "a");
            else
                hudRenderTarget = findMethod(env, hudClass, "(F)V", "a", "func_175180_a", "renderGameOverlay");
            env->DeleteLocalRef(hudClassObject);
        }
    }
    if (!clientTickTarget || !worldRenderTarget || !hudRenderTarget) {
        return startJavaFallback(env, bootstrapClass, report);
    }

    clientTickCallback = env->GetStaticMethodID(bootstrapClass, "onClientTick", "()V");
    if (clearException(env)) clientTickCallback = nullptr;
    worldRenderCallback = env->GetStaticMethodID(bootstrapClass, "onWorldRender", "(I)V");
    if (clearException(env)) worldRenderCallback = nullptr;
    hudRenderCallback = env->GetStaticMethodID(bootstrapClass, "onHudRender", "(F)V");
    if (clearException(env)) hudRenderCallback = nullptr;
    if (!clientTickCallback || !worldRenderCallback || !hudRenderCallback) {
        setReport(report, 13, L"PlayerESP Java callback methods were not found.");
        return false;
    }

    callbackClass = static_cast<jclass>(env->NewGlobalRef(bootstrapClass));
    if (!callbackClass) {
        setReport(report, 13, L"Failed to retain the PlayerESP callback class.");
        return false;
    }
    jvmtiEventCallbacks callbacks{};
    callbacks.Breakpoint = &onBreakpoint;
    error = jvmti->SetEventCallbacks(&callbacks, sizeof(callbacks));
    if (error == JVMTI_ERROR_NONE) {
        jlocation tickStart = 0, tickEnd = 0, renderStart = 0, renderEnd = 0, hudStart = 0, hudEnd = 0;
        error = jvmti->GetMethodLocation(clientTickTarget, &tickStart, &tickEnd);
        if (error == JVMTI_ERROR_NONE)
            error = jvmti->GetMethodLocation(worldRenderTarget, &renderStart, &renderEnd);
        if (error == JVMTI_ERROR_NONE)
            error = jvmti->GetMethodLocation(hudRenderTarget, &hudStart, &hudEnd);
        if (error == JVMTI_ERROR_NONE) error = jvmti->SetBreakpoint(clientTickTarget, tickStart);
        if (error == JVMTI_ERROR_NONE) error = jvmti->SetBreakpoint(worldRenderTarget, renderEnd);
        if (error == JVMTI_ERROR_NONE) error = jvmti->SetBreakpoint(hudRenderTarget, hudEnd);
        if (error == JVMTI_ERROR_NONE)
            error = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_BREAKPOINT, nullptr);
    }
    if (error != JVMTI_ERROR_NONE) {
        env->DeleteGlobalRef(callbackClass);
        callbackClass = nullptr;
        return startJavaFallback(env, bootstrapClass, report);
    }
    callbacksInstalled = true;
    return true;
}

static bool loadResourceBytes(int id, const unsigned char** data, DWORD* size) {
    HRSRC resource = FindResourceW(selfModule, MAKEINTRESOURCEW(id), RT_RCDATA);
    if (!resource) return false;
    HGLOBAL loaded = LoadResource(selfModule, resource);
    if (!loaded) return false;
    *size = SizeofResource(selfModule, resource);
    *data = static_cast<const unsigned char*>(LockResource(loaded));
    return *data && *size > 0;
}

static bool installEmbeddedPayload(JNIEnv* env, jobject loader, RuntimeKind kind) {
    const unsigned char* bytes = nullptr;
    DWORD size = 0;
    const int resourceId = kind == RuntimeKind::NAMED ? 103
        : kind == RuntimeKind::SRG ? 101 : 102;
    if (!loadResourceBytes(resourceId, &bytes, &size)) return false;
    wchar_t temporaryDirectory[MAX_PATH]{};
    if (!GetTempPathW(MAX_PATH, temporaryDirectory)) return false;
    wchar_t payloadPath[MAX_PATH]{};
    const int payloadKind = kind == RuntimeKind::NAMED ? 3 : kind == RuntimeKind::SRG ? 1 : 2;
    swprintf(payloadPath, MAX_PATH, L"%s%s_%lu_%d.jar", temporaryDirectory,
        INJECTOR_PAYLOAD_PREFIX, GetCurrentProcessId(), payloadKind);
    HANDLE fileHandle = CreateFileW(payloadPath, GENERIC_WRITE, FILE_SHARE_READ, nullptr,
        CREATE_ALWAYS, FILE_ATTRIBUTE_TEMPORARY, nullptr);
    if (fileHandle == INVALID_HANDLE_VALUE) return false;
    DWORD written = 0;
    const bool writeSucceeded = WriteFile(fileHandle, bytes, size, &written, nullptr)
        && written == size;
    CloseHandle(fileHandle);
    if (!writeSucceeded) { DeleteFileW(payloadPath); return false; }

    jclass fileClass = env->FindClass("java/io/File");
    jmethodID fileConstructor = env->GetMethodID(fileClass, "<init>", "(Ljava/lang/String;)V");
    jmethodID deleteOnExit = env->GetMethodID(fileClass, "deleteOnExit", "()V");
    jmethodID toUri = env->GetMethodID(fileClass, "toURI", "()Ljava/net/URI;");
    jstring pathString = env->NewString(reinterpret_cast<const jchar*>(payloadPath),
        static_cast<jsize>(std::wcslen(payloadPath)));
    jobject file = env->NewObject(fileClass, fileConstructor, pathString);
    env->CallVoidMethod(file, deleteOnExit);
    jobject uri = env->CallObjectMethod(file, toUri);
    jclass uriClass = env->FindClass("java/net/URI");
    jmethodID toUrl = env->GetMethodID(uriClass, "toURL", "()Ljava/net/URL;");
    jobject url = env->CallObjectMethod(uri, toUrl);
    jclass loaderClass = env->GetObjectClass(loader);
    jmethodID addUrl = env->GetMethodID(loaderClass, "addURL", "(Ljava/net/URL;)V");
    if (env->ExceptionCheck() || !file || !url || !addUrl) return false;
    env->CallVoidMethod(loader, addUrl, url);
    return !env->ExceptionCheck();
}

extern "C" __declspec(dllexport) DWORD WINAPI playeresp_init(void* parameter) {
    auto* report = static_cast<BootstrapReport*>(parameter);
    if (report) { report->stage = 0; report->message[0] = L'\0'; }
    HMODULE jvm = GetModuleHandleW(L"jvm.dll");
    if (!jvm) return 3;
    auto getVMs = reinterpret_cast<GetCreatedJavaVMs>(GetProcAddress(jvm, "JNI_GetCreatedJavaVMs"));
    if (!getVMs) return 4;
    JavaVM* vm = nullptr;
    jsize vmCount = 0;
    if (getVMs(&vm, 1, &vmCount) != JNI_OK || !vm || vmCount == 0) return 5;
    JNIEnv* env = nullptr;
    if (vm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr) != JNI_OK) return 6;
    jclass minecraftClass = nullptr;
    jobject minecraftInstance = nullptr;
    RuntimeKind kind = RuntimeKind::NONE;
    RuntimeProbe probe;
    for (int attempt = 0; attempt < 200 && kind == RuntimeKind::NONE; attempt++) {
        kind = findMinecraftRuntime(env, vm, &minecraftClass, &minecraftInstance, &probe);
        if (kind == RuntimeKind::NONE) Sleep(50);
    }
    if (kind == RuntimeKind::NONE) {
        if (report) {
            report->stage = 7;
            if (!probe.jvmtiAvailable) {
                std::wcsncpy(report->message, L"JVMTI is unavailable in this JVM.", 511);
            } else if (!probe.classesAvailable) {
                std::wcsncpy(report->message, L"JVMTI did not return the loaded classes.", 511);
            } else if (probe.namedClassFound || probe.obfuscatedClassFound) {
                std::wcsncpy(report->message, L"Minecraft class found, but its singleton instance was unavailable.", 511);
            } else {
                swprintf(report->message, 512, L"Minecraft class signature was not present among %d loaded classes.",
                    probe.loadedClassCount);
            }
            report->message[511] = L'\0';
        }
        vm->DetachCurrentThread();
        return 7;
    }
    jclass classClass = env->FindClass("java/lang/Class");
    jmethodID getClassLoader = env->GetMethodID(classClass, "getClassLoader", "()Ljava/lang/ClassLoader;");
    jobject loader = env->CallObjectMethod(minecraftClass, getClassLoader);
    if (!loader || clearException(env)) { vm->DetachCurrentThread(); return 8; }
    if (!installEmbeddedPayload(env, loader, kind)) {
        if (env->ExceptionCheck()) captureException(env, report, 9);
        vm->DetachCurrentThread();
        return 9;
    }
    jclass classLoaderClass = env->FindClass("java/lang/ClassLoader");
    jmethodID loadClass = env->GetMethodID(classLoaderClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    jstring bootstrapName = env->NewStringUTF(INJECTOR_BOOTSTRAP_CLASS);
    jobject bootstrapObject = env->CallObjectMethod(loader, loadClass, bootstrapName);
    if (!bootstrapObject || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) captureException(env, report, 10);
        vm->DetachCurrentThread();
        return 10;
    }
    jclass bootstrapClass = static_cast<jclass>(bootstrapObject);
    jmethodID start = env->GetStaticMethodID(bootstrapClass, "start", "()V");
    if (!start || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) captureException(env, report, 11);
        vm->DetachCurrentThread();
        return 11;
    }
    env->CallStaticVoidMethod(bootstrapClass, start);
    if (env->ExceptionCheck()) {
        captureException(env, report, 12);
        vm->DetachCurrentThread();
        return 12;
    }
    if (!installRuntimeCallbacks(env, vm, minecraftClass, bootstrapClass, kind, report)) {
        vm->DetachCurrentThread();
        return 13;
    }
    vm->DetachCurrentThread();
    return 0;
}

BOOL APIENTRY DllMain(HMODULE module, DWORD reason, LPVOID) {
    if (reason == DLL_PROCESS_ATTACH) {
        selfModule = module;
        DisableThreadLibraryCalls(module);
    }
    return TRUE;
}
