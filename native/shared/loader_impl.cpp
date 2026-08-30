#include <windows.h>
#include <tlhelp32.h>
#include <conio.h>
#include <filesystem>
#include <iostream>
#include <algorithm>
#include <cstdint>
#include <cwctype>
#include <map>
#include <set>
#include <string>
#include <vector>

#ifndef INJECTOR_CONSOLE_TITLE
#define INJECTOR_CONSOLE_TITLE L"PlayerESP Loader"
#endif
#ifndef INJECTOR_LIBRARY_FILE
#define INJECTOR_LIBRARY_FILE L"playeresp_library.dll"
#endif
#ifndef INJECTOR_ENTRY_FILE
#define INJECTOR_ENTRY_FILE L"playeresp_entry.dll"
#endif
#ifndef INJECTOR_BOOTSTRAP_EXPORT
#define INJECTOR_BOOTSTRAP_EXPORT "PlayerEspBootstrap"
#endif
#ifndef INJECTOR_SUCCESS_MESSAGE
#define INJECTOR_SUCCESS_MESSAGE L"Success. Press \\ in Minecraft."
#endif

struct Candidate {
    DWORD pid;
    bool java;
    bool minecraftWindow;
};

struct BootstrapReport {
    DWORD stage;
    wchar_t message[512];
};

static DWORD lastFailureStage = 0;
static std::wstring lastFailureMessage;
static int finish(const wchar_t* message, int code);

static std::wstring lower(std::wstring text) {
    std::transform(text.begin(), text.end(), text.begin(), [](wchar_t value) {
        return static_cast<wchar_t>(std::towlower(value));
    });
    return text;
}

static BOOL CALLBACK collectMinecraftWindows(HWND window, LPARAM parameter) {
    if (!IsWindowVisible(window) || GetWindowTextLengthW(window) <= 0) return TRUE;
    wchar_t title[512]{};
    GetWindowTextW(window, title, 512);
    const std::wstring normalized = lower(title);
    if (normalized.find(L"minecraft") == std::wstring::npos
        && normalized.find(L"lunar client") == std::wstring::npos
        && normalized.find(L"badlion") == std::wstring::npos) return TRUE;
    DWORD pid = 0;
    GetWindowThreadProcessId(window, &pid);
    if (pid) reinterpret_cast<std::set<DWORD>*>(parameter)->insert(pid);
    return TRUE;
}

static std::vector<DWORD> findJavaProcesses() {
    std::map<DWORD, Candidate> candidates;
    std::set<DWORD> minecraftWindows;
    EnumWindows(collectMinecraftWindows, reinterpret_cast<LPARAM>(&minecraftWindows));
    HANDLE snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (snapshot == INVALID_HANDLE_VALUE) return {};
    PROCESSENTRY32W entry{};
    entry.dwSize = sizeof(entry);
    if (Process32FirstW(snapshot, &entry)) {
        do {
            const bool java = _wcsicmp(entry.szExeFile, L"javaw.exe") == 0
                || _wcsicmp(entry.szExeFile, L"java.exe") == 0;
            const bool minecraft = minecraftWindows.count(entry.th32ProcessID) != 0;
            if (java)
                candidates[entry.th32ProcessID] = {entry.th32ProcessID, java, minecraft};
        } while (Process32NextW(snapshot, &entry));
    }
    CloseHandle(snapshot);
    std::vector<Candidate> ordered;
    for (const auto& item : candidates) ordered.push_back(item.second);
    std::sort(ordered.begin(), ordered.end(), [](const Candidate& left, const Candidate& right) {
        int leftScore = (left.minecraftWindow ? 2 : 0) + (left.java ? 1 : 0);
        int rightScore = (right.minecraftWindow ? 2 : 0) + (right.java ? 1 : 0);
        return leftScore != rightScore ? leftScore > rightScore : left.pid < right.pid;
    });
    const bool hasMinecraftWindow = std::any_of(ordered.begin(), ordered.end(), [](const Candidate& candidate) {
        return candidate.minecraftWindow;
    });
    std::vector<DWORD> result;
    for (const Candidate& candidate : ordered) {
        if (!hasMinecraftWindow || candidate.minecraftWindow) result.push_back(candidate.pid);
    }
    return result;
}

static std::uintptr_t findRemoteModule(DWORD pid, const std::wstring& fileName) {
    for (int attempt = 0; attempt < 40; attempt++) {
        HANDLE snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPMODULE | TH32CS_SNAPMODULE32, pid);
        if (snapshot != INVALID_HANDLE_VALUE) {
            MODULEENTRY32W entry{};
            entry.dwSize = sizeof(entry);
            if (Module32FirstW(snapshot, &entry)) {
                do {
                    if (_wcsicmp(entry.szModule, fileName.c_str()) == 0) {
                        std::uintptr_t address = reinterpret_cast<std::uintptr_t>(entry.modBaseAddr);
                        CloseHandle(snapshot);
                        return address;
                    }
                } while (Module32NextW(snapshot, &entry));
            }
            CloseHandle(snapshot);
        }
        Sleep(50);
    }
    return 0;
}

static bool loadRemoteLibrary(HANDLE process, DWORD pid, const std::filesystem::path& dll, DWORD stage) {
    lastFailureStage = stage;
    const std::wstring path = dll.wstring();
    const SIZE_T bytes = (path.size() + 1) * sizeof(wchar_t);
    void* remote = VirtualAllocEx(process, nullptr, bytes, MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
    if (!remote || !WriteProcessMemory(process, remote, path.c_str(), bytes, nullptr)) {
        if (remote) VirtualFreeEx(process, remote, 0, MEM_RELEASE);
        return false;
    }
    auto loadLibrary = reinterpret_cast<LPTHREAD_START_ROUTINE>(
        GetProcAddress(GetModuleHandleW(L"kernel32.dll"), "LoadLibraryW"));
    HANDLE thread = CreateRemoteThread(process, nullptr, 0, loadLibrary, remote, 0, nullptr);
    if (!thread) {
        VirtualFreeEx(process, remote, 0, MEM_RELEASE);
        return false;
    }
    DWORD wait = WaitForSingleObject(thread, 10000);
    CloseHandle(thread);
    if (wait == WAIT_OBJECT_0) VirtualFreeEx(process, remote, 0, MEM_RELEASE);
    return wait == WAIT_OBJECT_0 && findRemoteModule(pid, dll.filename().wstring()) != 0;
}

static bool inject(DWORD pid, const std::filesystem::path& library, const std::filesystem::path& dll) {
    lastFailureStage = 100;
    lastFailureMessage.clear();
    HANDLE process = OpenProcess(PROCESS_CREATE_THREAD | PROCESS_QUERY_INFORMATION |
        PROCESS_VM_OPERATION | PROCESS_VM_WRITE | PROCESS_VM_READ, FALSE, pid);
    if (!process) return false;
    if (!loadRemoteLibrary(process, pid, library, 101)) { CloseHandle(process); return false; }
    if (!loadRemoteLibrary(process, pid, dll, 102)) { CloseHandle(process); return false; }

    std::uintptr_t remoteModule = findRemoteModule(pid, dll.filename().wstring());
    HMODULE localModule = LoadLibraryExW(dll.c_str(), nullptr, DONT_RESOLVE_DLL_REFERENCES);
    FARPROC localBootstrap = localModule ? GetProcAddress(localModule, INJECTOR_BOOTSTRAP_EXPORT) : nullptr;
    lastFailureStage = remoteModule ? 105 : 104;
    if (!remoteModule || !localBootstrap) {
        if (localModule) FreeLibrary(localModule);
        CloseHandle(process);
        return false;
    }
    std::uintptr_t offset = reinterpret_cast<std::uintptr_t>(localBootstrap)
        - reinterpret_cast<std::uintptr_t>(localModule);
    FreeLibrary(localModule);
    auto remoteBootstrap = reinterpret_cast<LPTHREAD_START_ROUTINE>(remoteModule + offset);
    BootstrapReport emptyReport{};
    void* remoteReport = VirtualAllocEx(process, nullptr, sizeof(BootstrapReport),
        MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
    if (!remoteReport || !WriteProcessMemory(process, remoteReport, &emptyReport, sizeof(emptyReport), nullptr)) {
        if (remoteReport) VirtualFreeEx(process, remoteReport, 0, MEM_RELEASE);
        CloseHandle(process);
        lastFailureStage = 108;
        return false;
    }
    lastFailureStage = 106;
    HANDLE bootstrapThread = CreateRemoteThread(process, nullptr, 0, remoteBootstrap, remoteReport, 0, nullptr);
    if (!bootstrapThread) {
        VirtualFreeEx(process, remoteReport, 0, MEM_RELEASE);
        CloseHandle(process);
        return false;
    }
    DWORD bootstrapWait = WaitForSingleObject(bootstrapThread, 20000);
    DWORD bootstrapResult = STILL_ACTIVE;
    if (bootstrapWait == WAIT_OBJECT_0) GetExitCodeThread(bootstrapThread, &bootstrapResult);
    BootstrapReport report{};
    if (ReadProcessMemory(process, remoteReport, &report, sizeof(report), nullptr) && report.message[0])
        lastFailureMessage.assign(report.message);
    if (bootstrapWait == WAIT_OBJECT_0) VirtualFreeEx(process, remoteReport, 0, MEM_RELEASE);
    CloseHandle(bootstrapThread);
    CloseHandle(process);
    lastFailureStage = bootstrapWait == WAIT_OBJECT_0 ? bootstrapResult : 107;
    return bootstrapWait == WAIT_OBJECT_0 && bootstrapResult == 0;
}

static int finishFailure(int code) {
    std::wstring message = L"Not found. ";
    message += lastFailureStage < 100 ? L"Hook stage " : L"Loader stage ";
    message += std::to_wstring(lastFailureStage);
    if (!lastFailureMessage.empty()) message += L": " + lastFailureMessage;
    return finish(message.c_str(), code);
}

static int finish(const wchar_t* message, int code) {
    std::wcout << L"\n" << message << L"\n\nPress any key to continue..." << std::flush;
    _getwch();
    return code;
}

static int failureScore(DWORD stage) {
    if (stage == 0) return -1;
    if (stage < 100) return 1000 + static_cast<int>(stage);
    return static_cast<int>(stage);
}

int wmain(int argc, wchar_t** argv) {
    SetConsoleTitleW(INJECTOR_CONSOLE_TITLE);
    const std::filesystem::path directory = std::filesystem::absolute(std::filesystem::path(argv[0])).parent_path();
    const std::filesystem::path library = directory / INJECTOR_LIBRARY_FILE;
    const std::filesystem::path dll = directory / INJECTOR_ENTRY_FILE;
    std::filesystem::remove(directory / L"playeresp_loader.log");
    std::filesystem::remove(directory / L"playeresp_hook.log");
    if (!std::filesystem::exists(library) || !std::filesystem::exists(dll)) return finish(L"Not found.", 1);
    if (argc > 1) {
        DWORD pid = static_cast<DWORD>(std::wcstoul(argv[1], nullptr, 10));
        std::wcout << L"Trying PID " << pid << L"...\n";
        return inject(pid, library, dll) ? finish(INJECTOR_SUCCESS_MESSAGE, 0) : finishFailure(2);
    }
    DWORD bestFailureStage = 0;
    std::wstring bestFailureMessage;
    for (int round = 1; round <= 4; round++) {
        for (DWORD pid : findJavaProcesses()) {
            std::wcout << L"Trying Java PID " << pid << L"...\n";
            if (inject(pid, library, dll)) return finish(INJECTOR_SUCCESS_MESSAGE, 0);
            if (lastFailureStage >= 8 && lastFailureStage < 100) return finishFailure(3);
            if (failureScore(lastFailureStage) > failureScore(bestFailureStage)) {
                bestFailureStage = lastFailureStage;
                bestFailureMessage = lastFailureMessage;
            }
        }
        if (round < 4) { std::wcout << L"Searching again...\n"; Sleep(750); }
    }
    if (bestFailureStage) {
        lastFailureStage = bestFailureStage;
        lastFailureMessage = bestFailureMessage;
    }
    return finishFailure(3);
}
