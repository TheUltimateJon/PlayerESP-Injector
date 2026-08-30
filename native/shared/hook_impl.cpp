#include <windows.h>

#ifndef INJECTOR_LIBRARY_FILE
#define INJECTOR_LIBRARY_FILE L"playeresp_library.dll"
#endif
#ifndef INJECTOR_INIT_EXPORT
#define INJECTOR_INIT_EXPORT "playeresp_init"
#endif

struct BootstrapReport {
    DWORD stage;
    wchar_t message[512];
};

using PlayerEspInit = DWORD(WINAPI*)(void*);

extern "C" __declspec(dllexport) DWORD WINAPI PlayerEspBootstrap(void* parameter) {
    HMODULE library = GetModuleHandleW(INJECTOR_LIBRARY_FILE);
    if (!library) return 1;
    auto initialize = reinterpret_cast<PlayerEspInit>(GetProcAddress(library, INJECTOR_INIT_EXPORT));
    if (!initialize) return 2;
    return initialize(parameter);
}

BOOL APIENTRY DllMain(HMODULE module, DWORD reason, LPVOID) {
    if (reason == DLL_PROCESS_ATTACH) DisableThreadLibraryCalls(module);
    return TRUE;
}
