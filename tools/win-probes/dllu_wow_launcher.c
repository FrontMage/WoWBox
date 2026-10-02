#include <windows.h>
#include <tlhelp32.h>
#include <stdarg.h>
#include <stdio.h>
#include <wchar.h>

static const wchar_t *const kBattleNetExe =
    L"C:\\Program Files (x86)\\Battle.net\\Battle.net Launcher.exe";
static const wchar_t *const kWowExe =
    L"C:\\Program Files (x86)\\World of Warcraft\\_anniversary_\\WowClassic.exe";
static const wchar_t *const kLogPath =
    L"C:\\windows\\temp\\dllu-wow-launcher.log";

static void log_line(const wchar_t *format, ...) {
    wchar_t message[1024];
    va_list args;
    va_start(args, format);
    _vsnwprintf(message, ARRAYSIZE(message) - 1, format, args);
    va_end(args);
    message[ARRAYSIZE(message) - 1] = L'\0';

    HANDLE file = CreateFileW(kLogPath, FILE_APPEND_DATA,
                              FILE_SHARE_READ | FILE_SHARE_WRITE, NULL,
                              OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    if (file == INVALID_HANDLE_VALUE) return;

    SYSTEMTIME now;
    wchar_t line[1200];
    GetLocalTime(&now);
    int length = _snwprintf(line, ARRAYSIZE(line) - 1,
                            L"%04u-%02u-%02u %02u:%02u:%02u pid=%lu %ls\r\n",
                            now.wYear, now.wMonth, now.wDay,
                            now.wHour, now.wMinute, now.wSecond,
                            GetCurrentProcessId(), message);
    if (length > 0) {
        DWORD written = 0;
        WriteFile(file, line, (DWORD)length * sizeof(wchar_t), &written, NULL);
    }
    CloseHandle(file);
}

static DWORD find_process_id(const wchar_t *image_name) {
    HANDLE snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (snapshot == INVALID_HANDLE_VALUE) return 0;

    PROCESSENTRY32W entry = {0};
    entry.dwSize = sizeof(entry);
    DWORD process_id = 0;
    if (Process32FirstW(snapshot, &entry)) {
        do {
            if (_wcsicmp(entry.szExeFile, image_name) == 0) {
                process_id = entry.th32ProcessID;
                break;
            }
        } while (Process32NextW(snapshot, &entry));
    }
    CloseHandle(snapshot);
    return process_id;
}

static BOOL process_exists(const wchar_t *image_name) {
    return find_process_id(image_name) != 0;
}

static BOOL wait_for_process(const wchar_t *image_name, DWORD timeout_ms) {
    DWORD started = GetTickCount();
    do {
        if (process_exists(image_name)) return TRUE;
        Sleep(500);
    } while (GetTickCount() - started < timeout_ms);
    return process_exists(image_name);
}

struct window_search {
    DWORD process_id;
    HWND window;
};

static BOOL CALLBACK find_visible_window(HWND window, LPARAM parameter) {
    struct window_search *search = (struct window_search *)parameter;
    DWORD owner = 0;
    GetWindowThreadProcessId(window, &owner);
    if (owner != search->process_id || !IsWindowVisible(window) || GetWindow(window, GW_OWNER)) {
        return TRUE;
    }
    search->window = window;
    return FALSE;
}

static BOOL focus_process_window(DWORD process_id, DWORD timeout_ms) {
    DWORD started = GetTickCount();
    struct window_search search = {0};
    search.process_id = process_id;
    do {
        search.window = NULL;
        EnumWindows(find_visible_window, (LPARAM)&search);
        if (search.window) {
            ShowWindow(search.window, SW_RESTORE);
            SetWindowPos(search.window, HWND_TOP, 0, 0, 0, 0,
                         SWP_NOMOVE | SWP_NOSIZE | SWP_SHOWWINDOW);
            SetForegroundWindow(search.window);
            log_line(L"focused WowClassic window hwnd=%p pid=%lu", search.window, process_id);
            return TRUE;
        }
        Sleep(500);
    } while (GetTickCount() - started < timeout_ms);
    log_line(L"no visible WowClassic window appeared for pid=%lu", process_id);
    return FALSE;
}

static BOOL launch_process(const wchar_t *application, const wchar_t *arguments,
                           const wchar_t *working_directory) {
    wchar_t command_line[2048];
    if (arguments && arguments[0]) {
        _snwprintf(command_line, ARRAYSIZE(command_line) - 1,
                   L"\"%ls\" %ls", application, arguments);
    } else {
        _snwprintf(command_line, ARRAYSIZE(command_line) - 1,
                   L"\"%ls\"", application);
    }
    command_line[ARRAYSIZE(command_line) - 1] = L'\0';

    STARTUPINFOW startup = {0};
    PROCESS_INFORMATION process = {0};
    startup.cb = sizeof(startup);
    BOOL ok = CreateProcessW(application, command_line, NULL, NULL, FALSE,
                             CREATE_NEW_PROCESS_GROUP, NULL, working_directory,
                             &startup, &process);
    if (!ok) {
        log_line(L"CreateProcess failed error=%lu app=%ls args=%ls",
                 GetLastError(), application, arguments ? arguments : L"");
        return FALSE;
    }
    log_line(L"CreateProcess ok child=%lu app=%ls args=%ls",
             process.dwProcessId, application, arguments ? arguments : L"");
    CloseHandle(process.hThread);
    CloseHandle(process.hProcess);
    return TRUE;
}

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE previous, PWSTR command_line,
                    int show_command) {
    (void)instance;
    (void)previous;
    (void)command_line;
    (void)show_command;

    log_line(L"start");
    DWORD wow_process_id = find_process_id(L"WowClassic.exe");
    if (wow_process_id) {
        log_line(L"WowClassic.exe is already running pid=%lu", wow_process_id);
        focus_process_window(wow_process_id, 5000);
        return 0;
    }

    if (!process_exists(L"Battle.net.exe")) {
        log_line(L"Battle.net is not running; starting client first");
        if (!launch_process(kBattleNetExe, NULL,
                            L"C:\\Program Files (x86)\\Battle.net")) {
            return 10;
        }
        if (!wait_for_process(L"Battle.net.exe", 45000)) {
            log_line(L"Battle.net.exe did not become ready within 45 seconds");
            return 11;
        }
    }

    log_line(L"requesting official Battle.net WoWC launch");
    if (!launch_process(kBattleNetExe, L"--exec=launch WoWC",
                        L"C:\\Program Files (x86)\\Battle.net")) {
        return 12;
    }
    if (wait_for_process(L"WowClassic.exe", 75000)) {
        wow_process_id = find_process_id(L"WowClassic.exe");
        log_line(L"official Battle.net launch produced WowClassic.exe pid=%lu", wow_process_id);
        focus_process_window(wow_process_id, 120000);
        return 0;
    }

    if (!process_exists(L"Battle.net.exe")) {
        log_line(L"refusing direct fallback because Battle.net is no longer alive");
        return 13;
    }

    log_line(L"official launch timed out; using direct fallback with Battle.net alive");
    if (!launch_process(kWowExe,
                        L"-launcherlogin -initialgamemode=bccfresh "
                        L"-uid wow_classic_anniversary -d3d11",
                        L"C:\\Program Files (x86)\\World of Warcraft\\_anniversary_")) {
        return 14;
    }
    if (!wait_for_process(L"WowClassic.exe", 15000)) {
        log_line(L"direct fallback did not produce WowClassic.exe");
        return 15;
    }

    wow_process_id = find_process_id(L"WowClassic.exe");
    log_line(L"direct fallback produced WowClassic.exe pid=%lu", wow_process_id);
    focus_process_window(wow_process_id, 120000);
    return 0;
}
