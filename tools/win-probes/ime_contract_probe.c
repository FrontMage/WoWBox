#include <windows.h>
#include <stdint.h>
#include <stdio.h>

static HWND g_edit;
static WNDPROC g_edit_proc;
static HANDLE g_log = INVALID_HANDLE_VALUE;
static LONG g_sequence;

static uint64_t fnv1a_utf16(const WCHAR *text, int units) {
  uint64_t hash = UINT64_C(1469598103934665603);
  int i;
  for (i = 0; i < units; ++i) {
    hash ^= (uint16_t)text[i];
    hash *= UINT64_C(1099511628211);
  }
  return hash;
}

static void append_log(UINT message, WPARAM wparam, LPARAM lparam, LRESULT result) {
  WCHAR text[2048];
  char line[1024];
  DWORD written;
  int units = 0;
  uint64_t hash = 0;
  LONG sequence;
  if (g_log == INVALID_HANDLE_VALUE) return;
  if (g_edit) {
    units = GetWindowTextW(g_edit, text, ARRAYSIZE(text));
    if (units < 0) units = 0;
    hash = fnv1a_utf16(text, units);
  }
  sequence = InterlockedIncrement(&g_sequence);
  _snprintf(line, sizeof(line) - 1,
            "{\"sequence\":%ld,\"timestampMs\":%llu,\"message\":%u,"
            "\"wParam\":\"0x%llx\",\"lParam\":\"0x%llx\","
            "\"result\":\"0x%llx\",\"utf16Length\":%d,"
            "\"textFNV1a64\":\"%016llx\"}\r\n",
            sequence, (unsigned long long)GetTickCount64(), message,
            (unsigned long long)(uintptr_t)wparam,
            (unsigned long long)(uintptr_t)lparam,
            (unsigned long long)(uintptr_t)result,
            units, (unsigned long long)hash);
  line[sizeof(line) - 1] = '\0';
  WriteFile(g_log, line, (DWORD)strlen(line), &written, NULL);
  FlushFileBuffers(g_log);
}

static int is_traced_message(UINT message) {
  switch (message) {
    case WM_CHAR:
    case WM_UNICHAR:
    case WM_PASTE:
    case WM_KEYDOWN:
    case WM_KEYUP:
    case WM_SYSKEYDOWN:
    case WM_SYSKEYUP:
    case WM_IME_STARTCOMPOSITION:
    case WM_IME_COMPOSITION:
    case WM_IME_ENDCOMPOSITION:
      return 1;
    default:
      return 0;
  }
}

static LRESULT CALLBACK edit_subclass_proc(HWND hwnd, UINT message, WPARAM wparam, LPARAM lparam) {
  LRESULT result;
  if (message == WM_UNICHAR && wparam == UNICODE_NOCHAR) {
    append_log(message, wparam, lparam, TRUE);
    return TRUE;
  }
  result = CallWindowProcW(g_edit_proc, hwnd, message, wparam, lparam);
  if (is_traced_message(message)) append_log(message, wparam, lparam, result);
  return result;
}

static void open_log_file(void) {
  WCHAR path[MAX_PATH];
  WCHAR *slash;
  DWORD length = GetModuleFileNameW(NULL, path, ARRAYSIZE(path));
  if (!length || length >= ARRAYSIZE(path)) return;
  slash = wcsrchr(path, L'\\');
  if (slash) *(slash + 1) = L'\0';
  else path[0] = L'\0';
  lstrcatW(path, L"ime-contract-probe.jsonl");
  g_log = CreateFileW(path, FILE_APPEND_DATA, FILE_SHARE_READ | FILE_SHARE_WRITE,
                      NULL, OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
}

static LRESULT CALLBACK window_proc(HWND hwnd, UINT message, WPARAM wparam, LPARAM lparam) {
  switch (message) {
    case WM_CREATE: {
      HWND instructions = CreateWindowW(L"STATIC",
          L"Focus the edit box, then inject: abc / Chinese / A-Chinese-B / U+20BB7 / delete / reselect / Enter.\r\n"
          L"The JSONL records message shape and a UTF-16 hash, not plaintext.",
          WS_CHILD | WS_VISIBLE, 16, 14, 840, 48, hwnd, NULL, NULL, NULL);
      (void)instructions;
      g_edit = CreateWindowExW(WS_EX_CLIENTEDGE, L"EDIT", L"",
          WS_CHILD | WS_VISIBLE | WS_TABSTOP | ES_LEFT | ES_MULTILINE | ES_AUTOVSCROLL | WS_VSCROLL,
          16, 72, 840, 350, hwnd, (HMENU)1001, NULL, NULL);
      SendMessageW(g_edit, EM_SETLIMITTEXT, 16384, 0);
      g_edit_proc = (WNDPROC)SetWindowLongPtrW(g_edit, GWLP_WNDPROC, (LONG_PTR)edit_subclass_proc);
      SetFocus(g_edit);
      return 0;
    }
    case WM_SIZE:
      if (g_edit) MoveWindow(g_edit, 16, 72, LOWORD(lparam) - 32, HIWORD(lparam) - 88, TRUE);
      return 0;
    case WM_DESTROY:
      if (g_log != INVALID_HANDLE_VALUE) {
        CloseHandle(g_log);
        g_log = INVALID_HANDLE_VALUE;
      }
      PostQuitMessage(0);
      return 0;
    default:
      return DefWindowProcW(hwnd, message, wparam, lparam);
  }
}

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE previous, PWSTR command_line, int show) {
  WNDCLASSW wc;
  HWND window;
  MSG message;
  (void)previous;
  (void)command_line;
  ZeroMemory(&wc, sizeof(wc));
  wc.lpfnWndProc = window_proc;
  wc.hInstance = instance;
  wc.hCursor = LoadCursorW(NULL, IDC_IBEAM);
  wc.hbrBackground = (HBRUSH)(COLOR_WINDOW + 1);
  wc.lpszClassName = L"ImeContractProbeWindow";
  if (!RegisterClassW(&wc)) return 1;
  open_log_file();
  window = CreateWindowW(wc.lpszClassName, L"IME Contract Probe",
                         WS_OVERLAPPEDWINDOW | WS_VISIBLE,
                         CW_USEDEFAULT, CW_USEDEFAULT, 900, 520,
                         NULL, NULL, instance, NULL);
  if (!window) return 2;
  ShowWindow(window, show);
  UpdateWindow(window);
  while (GetMessageW(&message, NULL, 0, 0) > 0) {
    TranslateMessage(&message);
    DispatchMessageW(&message);
  }
  return (int)message.wParam;
}
