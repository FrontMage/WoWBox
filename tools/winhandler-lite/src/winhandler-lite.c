#include <winsock2.h>
#include <windows.h>
#include <shellapi.h>
#include <tlhelp32.h>
#include <psapi.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>
#include <string.h>
#include <wchar.h>

#ifdef _MSC_VER
#pragma comment(lib, "ws2_32")
#pragma comment(lib, "shell32")
#pragma comment(lib, "psapi")
#endif

#define CONTROL_PORT 7946
#define HOST_PORT 7947
#define IME_PORT 7952
#define BRIDGE_PORT 38442

#define PKT_LITE_READY 0x70
#define PKT_LITE_FOCUS 0x73
#define PKT_LITE_TEXT_STATUS 0x74
#define PKT_LITE_OPEN_URL 0x75
#define PKT_REQ_TEXT 0x01
#define PKT_TEXT_FLAG_SUBMIT 0x00000001
#define PKT_TEXT_FLAG_DELETE_BACKWARD 0x00000002
#define OPEN_URL_PACKET_VERSION 1
#define OPEN_URL_MAX_UTF8_BYTES 1800

#define REQ_EXIT 0
#define REQ_INIT 1
#define REQ_EXEC 2
#define REQ_KILL_PROCESS 3
#define REQ_LIST_PROCESSES 4
#define REQ_GET_PROCESS 5
#define REQ_SET_PROCESS_AFFINITY 6

#define MAX_PACKET 8192
#define MAX_CMD 4096
#define MAX_NAME 260

#define STAGE_RECV_TEXT 1
#define STAGE_DONE 4
#define STAGE_INVALID_PACKET 5
#define STAGE_TARGET_RESOLVE 20
#define STAGE_MSG_INJECT_BEGIN 21
#define STAGE_MSG_INJECT_END 22
#define STAGE_MSG_INJECT_FAILED 23
#define STAGE_MSG_INJECT_MODE 24
#define STAGE_CTX_ATTACH_FOCUS_OK 44
#define STAGE_CTX_ATTACH_FOCUS_ERR 45
#define STAGE_CTX_FG_HWND 46
#define STAGE_CTX_FOCUS_HWND 47
#define STAGE_CLIP_OPEN_BEGIN 50
#define STAGE_CLIP_OPEN_OK 51
#define STAGE_CLIP_OPEN_FAIL 52
#define STAGE_CLIP_SET_OK 53
#define STAGE_CLIP_SET_FAIL 54
#define STAGE_PASTE_SENDINPUT_BEGIN 55
#define STAGE_PASTE_SENDINPUT_OK 56
#define STAGE_PASTE_SENDINPUT_FAIL 57
#define STAGE_CLIP_RESTORE_OK 58
#define STAGE_CLIP_RESTORE_FAIL 59
#define STAGE_TARGET_PARENT 60
#define STAGE_WM_PASTE_BEGIN 61
#define STAGE_WM_PASTE_OK 62
#define STAGE_WM_PASTE_FAIL 63
#define STAGE_CLIP_RESTORE_DELAY 64
#define STAGE_BRIDGE_SENT_UNACKED 65
#define STAGE_BRIDGE_ACK 66

#define IME_ROUTE_NONE 0
#define IME_ROUTE_BRIDGE_38442 1
#define IME_ROUTE_WM_CHAR 2
#define IME_ROUTE_UNICODE_INPUT 3
#define IME_ROUTE_CLIPBOARD_CTRL_V 4
#define IME_ROUTE_KEY 5

#define IME_BACKEND_WM_CHAR 1
#define IME_BACKEND_UNICODE_INPUT 2
#define IME_BACKEND_CLIPBOARD_CTRL_V 3
#define IME_BACKEND_BRIDGE_DIAGNOSTIC 4

#pragma pack(push, 1)
typedef struct ime_text_status_packet {
  uint8_t type;
  uint8_t version;
  uint16_t size;
  int32_t req_id;
  uint16_t stage;
  uint16_t route;
  int32_t winerr;
  uint64_t hwnd;
  uint32_t pid;
  uint32_t tid;
  uint32_t utf16_units;
  int32_t aux;
} ime_text_status_packet_t;
#pragma pack(pop)
typedef char ime_text_status_packet_must_be_40_bytes[(sizeof(ime_text_status_packet_t) == 40) ? 1 : -1];

typedef struct startup_request {
  int valid;
  WCHAR workdir[MAX_PATH];
  WCHAR target[MAX_PATH];
  WCHAR params[MAX_CMD];
} startup_request_t;

static SOCKET g_control_socket = INVALID_SOCKET;
static SOCKET g_ime_socket = INVALID_SOCKET;
static int g_control_mode = 0;
static int g_wow_ime_focus = 0;
static int g_ime_backend = IME_BACKEND_WM_CHAR;
static uint16_t g_status_route = IME_ROUTE_NONE;
static HWND g_status_hwnd = NULL;
static uint32_t g_status_pid = 0;
static uint32_t g_status_tid = 0;
static uint32_t g_status_utf16_units = 0;
static int32_t g_bridge_pending_req_id = 0;
static uint32_t g_bridge_pending_utf16_units = 0;
static struct sockaddr_in g_host_addr;
static struct sockaddr_in g_bridge_addr;

static size_t safe_wcsnlen_local(const WCHAR *s, size_t cap) {
  size_t i = 0;
  if (!s) return 0;
  while (i < cap && s[i]) ++i;
  return i;
}

static void send_log(int32_t req_id, uint8_t stage, int32_t winerr, int32_t aux);

static void debug_log(const char *fmt, ...) {
  char line[1024];
  va_list ap;
  va_start(ap, fmt);
  _vsnprintf(line, sizeof(line) - 1, fmt, ap);
  va_end(ap);
  line[sizeof(line) - 1] = '\0';
  fprintf(stderr, "winhandler-lite: %s\n", line);
  fflush(stderr);
}

static void send_control_packet(const void *data, int len) {
  if (g_control_socket == INVALID_SOCKET || !data || len <= 0) return;
  sendto(g_control_socket, (const char *)data, len, 0,
         (const struct sockaddr *)&g_host_addr, sizeof(g_host_addr));
}

static int has_http_url_scheme(const WCHAR *url) {
  if (!url) return 0;
  return _wcsnicmp(url, L"http://", 7) == 0 ||
         _wcsnicmp(url, L"https://", 8) == 0;
}

/*
 * Returns -1 when this is not an --open-url invocation. All other return
 * values are process exit codes for the one-shot URL bridge mode.
 */
static int try_send_open_url_command(void) {
  LPWSTR *argv;
  int argc = 0;
  const WCHAR *url;
  int wide_len;
  int utf8_len;
  int packet_len;
  uint8_t packet[6 + OPEN_URL_MAX_UTF8_BYTES];
  SOCKET sock;
  int sent;
  int i;

  argv = CommandLineToArgvW(GetCommandLineW(), &argc);
  if (!argv) return -1;
  if (argc < 2 || _wcsicmp(argv[1], L"--open-url") != 0) {
    LocalFree(argv);
    return -1;
  }
  if (argc != 3 || !has_http_url_scheme(argv[2])) {
    LocalFree(argv);
    return 2;
  }

  url = argv[2];
  wide_len = (int)wcslen(url);
  for (i = 0; i < wide_len; ++i) {
    if (url[i] < 0x20 || url[i] == 0x7f) {
      LocalFree(argv);
      return 2;
    }
  }

  utf8_len = WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, url, wide_len,
                                 NULL, 0, NULL, NULL);
  if (utf8_len <= 0 || utf8_len > OPEN_URL_MAX_UTF8_BYTES) {
    LocalFree(argv);
    return 2;
  }

  packet_len = 6 + utf8_len;
  packet[0] = PKT_LITE_OPEN_URL;
  packet[1] = OPEN_URL_PACKET_VERSION;
  packet[2] = (uint8_t)(packet_len & 0xff);
  packet[3] = (uint8_t)((packet_len >> 8) & 0xff);
  packet[4] = (uint8_t)(utf8_len & 0xff);
  packet[5] = (uint8_t)((utf8_len >> 8) & 0xff);
  if (WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, url, wide_len,
                          (char *)packet + 6, utf8_len, NULL, NULL) != utf8_len) {
    LocalFree(argv);
    return 2;
  }
  LocalFree(argv);

  sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
  if (sock == INVALID_SOCKET) return 3;
  sent = sendto(sock, (const char *)packet, packet_len, 0,
                (const struct sockaddr *)&g_host_addr, sizeof(g_host_addr));
  closesocket(sock);
  return sent == packet_len ? 0 : 3;
}

static int send_ime_packet(const void *data, int len) {
  int sent;
  if (g_ime_socket == INVALID_SOCKET || !data || len <= 0) return -1;
  sent = sendto(g_ime_socket, (const char *)data, len, 0,
                (const struct sockaddr *)&g_host_addr, sizeof(g_host_addr));
  return sent;
}

static void set_status_target(HWND hwnd) {
  DWORD pid = 0;
  DWORD tid = 0;
  g_status_hwnd = hwnd;
  if (hwnd) tid = GetWindowThreadProcessId(hwnd, &pid);
  g_status_pid = (uint32_t)pid;
  g_status_tid = (uint32_t)tid;
}

static void send_log(int32_t req_id, uint8_t stage, int32_t winerr, int32_t aux) {
  ime_text_status_packet_t packet;
  ZeroMemory(&packet, sizeof(packet));
  packet.type = PKT_LITE_TEXT_STATUS;
  packet.version = 1;
  packet.size = (uint16_t)sizeof(packet);
  packet.req_id = req_id;
  packet.stage = stage;
  packet.route = g_status_route;
  packet.winerr = winerr;
  packet.hwnd = (uint64_t)(uintptr_t)g_status_hwnd;
  packet.pid = g_status_pid;
  packet.tid = g_status_tid;
  packet.utf16_units = g_status_utf16_units;
  packet.aux = aux;
  send_ime_packet(&packet, (int)sizeof(packet));
}

static void send_init(void) {
  uint8_t b = REQ_INIT;
  send_control_packet(&b, 1);
}

static void send_ready(void) {
  uint8_t b = PKT_LITE_READY;
  send_ime_packet(&b, 1);
}

static void send_focus_to_host(int focus, const char *box_name) {
  uint16_t n = (uint16_t)strnlen(box_name ? box_name : "", 255);
  uint8_t payload[1 + 1 + 2 + 255];
  int payload_len;
  int sent;
  payload[0] = PKT_LITE_FOCUS;
  payload[1] = (uint8_t)(focus ? 1 : 0);
  payload[2] = (uint8_t)(n & 0xff);
  payload[3] = (uint8_t)((n >> 8) & 0xff);
  if (n > 0) memcpy(payload + 4, box_name, n);
  payload_len = 4 + n;
  sent = send_ime_packet(payload, payload_len);
  debug_log("ime_focus forward focus=%d box=%s box_len=%u packet_len=%d sent=%d err=%lu",
            focus ? 1 : 0,
            box_name ? box_name : "",
            (unsigned)n,
            payload_len,
            sent,
            sent == SOCKET_ERROR ? GetLastError() : 0);
}

static int json_get_int(const char *s, const char *key, int *out) {
  char needle[64];
  const char *p;
  char *endptr = NULL;
  long v;
  if (!s || !key || !out) return 0;
  _snprintf(needle, sizeof(needle), "\"%s\"", key);
  p = strstr(s, needle);
  if (!p) return 0;
  p = strchr(p, ':');
  if (!p) return 0;
  ++p;
  while (*p == ' ' || *p == '\t') ++p;
  v = strtol(p, &endptr, 10);
  if (p == endptr) return 0;
  *out = (int)v;
  return 1;
}

static int json_get_string(const char *s, const char *key, char *out, size_t out_cap) {
  char needle[64];
  const char *p;
  const char *q;
  size_t w = 0;
  if (!s || !key || !out || out_cap < 2) return 0;
  _snprintf(needle, sizeof(needle), "\"%s\"", key);
  p = strstr(s, needle);
  if (!p) return 0;
  p = strchr(p, ':');
  if (!p) return 0;
  p = strchr(p, '"');
  if (!p) return 0;
  ++p;
  q = p;
  while (*q && !(*q == '"' && q[-1] != '\\')) ++q;
  if (!*q) return 0;
  while (p < q && w + 1 < out_cap) {
    char c = *p++;
    if (c == '\\' && p < q) {
      char e = *p++;
      switch (e) {
        case '\\': c = '\\'; break;
        case '"': c = '"'; break;
        case 'n': c = '\n'; break;
        case 'r': c = '\r'; break;
        case 't': c = '\t'; break;
        default: c = e; break;
      }
    }
    out[w++] = c;
  }
  out[w] = '\0';
  return 1;
}

static int utf16le_to_utf8(const WCHAR *wtext, int wlen, char *out, int out_cap) {
  int n;
  if (!wtext || wlen <= 0 || !out || out_cap <= 1) return 0;
  n = WideCharToMultiByte(CP_UTF8, 0, wtext, wlen, out, out_cap - 1, NULL, NULL);
  if (n <= 0) return 0;
  out[n] = '\0';
  return n;
}

static int b64_encode(const uint8_t *src, int src_len, char *dst, int dst_cap) {
  static const char table[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
  int si = 0;
  int di = 0;
  if (!src || src_len < 0 || !dst || dst_cap <= 0) return 0;
  while (si < src_len) {
    int b0 = src[si++];
    int b1 = (si < src_len) ? src[si++] : -1;
    int b2 = (si < src_len) ? src[si++] : -1;
    if (di + 4 >= dst_cap) return 0;
    dst[di++] = table[(b0 >> 2) & 0x3f];
    dst[di++] = table[((b0 & 0x3) << 4) | ((b1 >= 0 ? b1 : 0) >> 4)];
    dst[di++] = (b1 >= 0) ? table[((b1 & 0xf) << 2) | ((b2 >= 0 ? b2 : 0) >> 6)] : '=';
    dst[di++] = (b2 >= 0) ? table[b2 & 0x3f] : '=';
  }
  if (di >= dst_cap) return 0;
  dst[di] = '\0';
  return di;
}

static WCHAR *capture_clipboard_unicode_copy(int *out_wchars) {
  HANDLE h;
  const WCHAR *src;
  WCHAR *copy;
  size_t n;

  if (!out_wchars) return NULL;
  *out_wchars = 0;
  h = GetClipboardData(CF_UNICODETEXT);
  if (!h) return NULL;

  src = (const WCHAR *)GlobalLock(h);
  if (!src) return NULL;

  n = wcslen(src);
  copy = (WCHAR *)malloc((n + 1) * sizeof(WCHAR));
  if (copy) {
    memcpy(copy, src, (n + 1) * sizeof(WCHAR));
    *out_wchars = (int)n;
  }
  GlobalUnlock(h);
  return copy;
}

static int set_clipboard_unicode(const WCHAR *text, int wchar_count) {
  SIZE_T bytes;
  HGLOBAL hmem;
  WCHAR *dst;
  if (!text || wchar_count < 0) return 0;
  bytes = (SIZE_T)(wchar_count + 1) * sizeof(WCHAR);
  hmem = GlobalAlloc(GMEM_MOVEABLE, bytes);
  if (!hmem) return 0;

  dst = (WCHAR *)GlobalLock(hmem);
  if (!dst) {
    GlobalFree(hmem);
    return 0;
  }

  memcpy(dst, text, (SIZE_T)wchar_count * sizeof(WCHAR));
  dst[wchar_count] = L'\0';
  GlobalUnlock(hmem);

  if (!SetClipboardData(CF_UNICODETEXT, hmem)) {
    GlobalFree(hmem);
    return 0;
  }
  return 1;
}

static int send_ctrl_v_input(void) {
  INPUT in[4];
  UINT sent;
  ZeroMemory(in, sizeof(in));

  in[0].type = INPUT_KEYBOARD;
  in[0].ki.wVk = VK_CONTROL;
  in[1].type = INPUT_KEYBOARD;
  in[1].ki.wVk = 'V';
  in[2].type = INPUT_KEYBOARD;
  in[2].ki.wVk = 'V';
  in[2].ki.dwFlags = KEYEVENTF_KEYUP;
  in[3].type = INPUT_KEYBOARD;
  in[3].ki.wVk = VK_CONTROL;
  in[3].ki.dwFlags = KEYEVENTF_KEYUP;

  sent = SendInput(4, in, sizeof(INPUT));
  return sent == 4 ? 1 : 0;
}

static int send_virtual_key(WORD vkey) {
  INPUT in[2];
  UINT sent;
  ZeroMemory(in, sizeof(in));
  in[0].type = INPUT_KEYBOARD;
  in[0].ki.wVk = vkey;
  in[1] = in[0];
  in[1].ki.dwFlags = KEYEVENTF_KEYUP;
  sent = SendInput(2, in, sizeof(INPUT));
  return sent == 2 ? 1 : 0;
}

static int send_wm_char_text(HWND hwnd, const WCHAR *text, int wchar_count, int submit) {
  int i;
  if (!hwnd || (!text && wchar_count > 0)) return 0;
  for (i = 0; i < wchar_count; ++i) {
    if (!PostMessageW(hwnd, WM_CHAR, (WPARAM)text[i], 1)) return 0;
  }
  if (submit) {
    if (!PostMessageW(hwnd, WM_KEYDOWN, VK_RETURN, 1)) return 0;
    if (!PostMessageW(hwnd, WM_CHAR, L'\r', 1)) return 0;
    if (!PostMessageW(hwnd, WM_KEYUP, VK_RETURN, 0xC0000001u)) return 0;
  }
  return 1;
}

static int send_unicode_input_text(const WCHAR *text, int wchar_count, int submit) {
  int i;
  INPUT in[2];
  if (!text && wchar_count > 0) return 0;
  for (i = 0; i < wchar_count; ++i) {
    ZeroMemory(in, sizeof(in));
    in[0].type = INPUT_KEYBOARD;
    in[0].ki.wScan = text[i];
    in[0].ki.dwFlags = KEYEVENTF_UNICODE;
    in[1] = in[0];
    in[1].ki.dwFlags = KEYEVENTF_UNICODE | KEYEVENTF_KEYUP;
    if (SendInput(2, in, sizeof(INPUT)) != 2) return 0;
  }
  return !submit || send_virtual_key(VK_RETURN);
}

static int send_wm_virtual_key(HWND hwnd, WORD vkey, WCHAR ch) {
  if (!hwnd) return 0;
  if (!PostMessageW(hwnd, WM_KEYDOWN, vkey, 1)) return 0;
  if (ch && !PostMessageW(hwnd, WM_CHAR, ch, 1)) return 0;
  return PostMessageW(hwnd, WM_KEYUP, vkey, 0xC0000001u) ? 1 : 0;
}

static void load_ime_backend(void) {
  WCHAR value[64];
  DWORD n = GetEnvironmentVariableW(L"WINLATOR_IME_BACKEND", value, ARRAYSIZE(value));
  g_ime_backend = IME_BACKEND_WM_CHAR;
  if (n == 0 || n >= ARRAYSIZE(value)) return;
  if (!_wcsicmp(value, L"wm_char")) g_ime_backend = IME_BACKEND_WM_CHAR;
  else if (!_wcsicmp(value, L"unicode") || !_wcsicmp(value, L"sendinput_unicode")) {
    g_ime_backend = IME_BACKEND_UNICODE_INPUT;
  }
  else if (!_wcsicmp(value, L"clipboard") || !_wcsicmp(value, L"clipboard_ctrl_v")) {
    g_ime_backend = IME_BACKEND_CLIPBOARD_CTRL_V;
  }
  else if (!_wcsicmp(value, L"bridge") || !_wcsicmp(value, L"bridge_38442")) {
    g_ime_backend = IME_BACKEND_BRIDGE_DIAGNOSTIC;
  }
}

static HWND resolve_target_window(int32_t req_id) {
  HWND hwnd_fg = GetForegroundWindow();
  HWND hwnd_focus = NULL;
  HWND target = hwnd_fg;
  DWORD self_tid = GetCurrentThreadId();
  DWORD fg_tid = 0;
  BOOL attached = FALSE;

  set_status_target(hwnd_fg);
  send_log(req_id, STAGE_CTX_FG_HWND, 0, 0);

  if (hwnd_fg) {
    fg_tid = GetWindowThreadProcessId(hwnd_fg, NULL);
  }

  if (fg_tid && fg_tid != self_tid) {
    attached = AttachThreadInput(self_tid, fg_tid, TRUE);
    send_log(req_id, attached ? STAGE_CTX_ATTACH_FOCUS_OK : STAGE_CTX_ATTACH_FOCUS_ERR,
             attached ? 0 : (int32_t)GetLastError(), attached ? 1 : 0);
  }

  hwnd_focus = GetFocus();
  if (!hwnd_focus && fg_tid) {
    GUITHREADINFO gti;
    ZeroMemory(&gti, sizeof(gti));
    gti.cbSize = sizeof(gti);
    if (GetGUIThreadInfo(fg_tid, &gti)) {
      hwnd_focus = gti.hwndFocus;
    }
  }

  if (attached) {
    AttachThreadInput(self_tid, fg_tid, FALSE);
  }

  set_status_target(hwnd_focus);
  send_log(req_id, STAGE_CTX_FOCUS_HWND, 0, 0);
  if (hwnd_focus) target = hwnd_focus;
  set_status_target(target);
  return target;
}

static int try_activate_target(HWND target, int32_t req_id) {
  HWND root;
  DWORD self_tid;
  DWORD target_tid;
  BOOL attached = FALSE;
  if (!target) return 0;
  root = GetAncestor(target, GA_ROOT);
  if (!root) root = target;

  self_tid = GetCurrentThreadId();
  target_tid = GetWindowThreadProcessId(target, NULL);
  if (target_tid && target_tid != self_tid) {
    attached = AttachThreadInput(self_tid, target_tid, TRUE);
    send_log(req_id, attached ? STAGE_CTX_ATTACH_FOCUS_OK : STAGE_CTX_ATTACH_FOCUS_ERR,
             attached ? 0 : (int32_t)GetLastError(), attached ? 2 : 0);
  }

  if (IsIconic(root)) ShowWindow(root, SW_RESTORE);
  SetForegroundWindow(root);
  SetFocus(target);
  SetActiveWindow(root);
  SetWindowPos(root, HWND_TOP, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE);

  if (attached) AttachThreadInput(self_tid, target_tid, FALSE);
  return 1;
}

static int send_bridge_commit(int32_t req_id, const WCHAR *wide, int wchar_count, int submit) {
  char utf8[4096];
  char b64[6144];
  char json[6400];
  int utf8_len;
  int b64_len;
  int n;

  utf8_len = utf16le_to_utf8(wide, wchar_count, utf8, (int)sizeof(utf8));
  if (utf8_len <= 0) {
    send_log(req_id, STAGE_MSG_INJECT_FAILED, (int32_t)GetLastError(), -11);
    return 0;
  }

  b64_len = b64_encode((const uint8_t *)utf8, utf8_len, b64, (int)sizeof(b64));
  if (b64_len <= 0) {
    send_log(req_id, STAGE_MSG_INJECT_FAILED, ERROR_INSUFFICIENT_BUFFER, -12);
    return 0;
  }

  n = _snprintf(json, sizeof(json) - 1,
                "{\"type\":\"ime_commit\",\"seq\":%d,\"submit\":%d,\"text_b64\":\"%s\"}",
                req_id, submit ? 1 : 0, b64);
  if (n <= 0) {
    send_log(req_id, STAGE_MSG_INJECT_FAILED, ERROR_INVALID_DATA, -13);
    return 0;
  }
  if (n > (int)sizeof(json) - 1) n = (int)sizeof(json) - 1;
  json[n] = '\0';

  if (g_ime_socket == INVALID_SOCKET) return 0;
  sendto(g_ime_socket, json, n, 0, (const struct sockaddr *)&g_bridge_addr, sizeof(g_bridge_addr));
  return 1;
}

static void handle_bridge_json_packet(const char *packet, int packet_len) {
  int focus = 0;
  int seq = 0;
  int ok = 0;
  int error = 0;
  char box[256];
  int has_box;
  (void)packet_len;
  box[0] = '\0';
  if (!packet || packet[0] != '{') return;
  if (strstr(packet, "\"type\":\"ime_commit_ack\"")) {
    if (!json_get_int(packet, "seq", &seq)) return;
    if (!json_get_int(packet, "ok", &ok)) ok = 0;
    if (!json_get_int(packet, "error", &error)) error = ok ? 0 : ERROR_GEN_FAILURE;
    g_status_route = IME_ROUTE_BRIDGE_38442;
    set_status_target(NULL);
    g_status_utf16_units = (seq == g_bridge_pending_req_id) ? g_bridge_pending_utf16_units : 0;
    send_log(seq, STAGE_BRIDGE_ACK, ok ? 0 : error, packet_len);
    send_log(seq, STAGE_DONE, ok ? 0 : error, 38442);
    if (seq == g_bridge_pending_req_id) {
      g_bridge_pending_req_id = 0;
      g_bridge_pending_utf16_units = 0;
    }
    return;
  }
  if (!strstr(packet, "\"type\":\"ime_focus\"")) return;
  if (!json_get_int(packet, "focus", &focus)) focus = 0;
  has_box = json_get_string(packet, "box", box, sizeof(box));
  debug_log("ime_focus recv json_len=%d focus=%d has_box=%d box=%s",
            packet_len,
            focus ? 1 : 0,
            has_box ? 1 : 0,
            box);
  g_wow_ime_focus = focus ? 1 : 0;
  send_focus_to_host(g_wow_ime_focus, box);
}

static void handle_text_request(const char *packet, int packet_len) {
  uint8_t req_type;
  int32_t req_id;
  int32_t text_bytes;
  int32_t flags = 0;
  int text_offset = 9;
  WCHAR wide[2049];
  int wchar_count;
  HWND hwnd;
  int old_wchars = 0;
  WCHAR *old_text = NULL;
  int submit;
  int delete_backward;
  int ok = 0;
  int32_t err = 0;

  if (packet_len < 9) {
    g_status_route = IME_ROUTE_NONE;
    set_status_target(NULL);
    g_status_utf16_units = 0;
    send_log(0, STAGE_INVALID_PACKET, ERROR_INVALID_DATA, packet_len);
    return;
  }

  req_type = (uint8_t)packet[0];
  req_id = *(const int32_t *)(packet + 1);
  text_bytes = *(const int32_t *)(packet + 5);

  if (text_bytes >= 0 && packet_len >= 13 && packet_len >= 13 + text_bytes) {
    flags = *(const int32_t *)(packet + 9);
    text_offset = 13;
  }

  submit = (flags & PKT_TEXT_FLAG_SUBMIT) ? 1 : 0;
  delete_backward = (flags & PKT_TEXT_FLAG_DELETE_BACKWARD) ? 1 : 0;
  g_status_route = IME_ROUTE_NONE;
  set_status_target(NULL);
  g_status_utf16_units = text_bytes > 0 ? (uint32_t)(text_bytes / 2) : 0;

  if (req_type != PKT_REQ_TEXT ||
      text_bytes < 0 ||
      text_bytes > 4096 ||
      (text_bytes == 0 && !submit && !delete_backward) ||
      (text_bytes & 1) != 0 ||
      packet_len < text_offset + text_bytes) {
    send_log(req_id, STAGE_INVALID_PACKET, ERROR_INVALID_DATA, text_bytes);
    return;
  }

  send_log(req_id, STAGE_RECV_TEXT, 0, text_bytes);

  wchar_count = text_bytes / 2;
  if (wchar_count > 2048) wchar_count = 2048;
  if (wchar_count > 0) memcpy(wide, packet + text_offset, wchar_count * sizeof(WCHAR));
  wide[wchar_count] = L'\0';

  if (g_ime_backend == IME_BACKEND_BRIDGE_DIAGNOSTIC && !delete_backward) {
    g_status_route = IME_ROUTE_BRIDGE_38442;
    send_log(req_id, STAGE_MSG_INJECT_BEGIN, 0, 901);
    if (send_bridge_commit(req_id, wide, wchar_count, submit)) {
      send_log(req_id, STAGE_MSG_INJECT_END, 0, 901);
      send_log(req_id, STAGE_MSG_INJECT_MODE, submit ? 903 : 902, wchar_count);
      /* 38442 has no proven consumer ACK. A successful UDP send is not a text-delivery ACK. */
      g_bridge_pending_req_id = req_id;
      g_bridge_pending_utf16_units = (uint32_t)wchar_count;
      send_log(req_id, STAGE_BRIDGE_SENT_UNACKED, ERROR_IO_PENDING, 38442);
    } else {
      err = (int32_t)WSAGetLastError();
      if (!err) err = ERROR_GEN_FAILURE;
      send_log(req_id, STAGE_MSG_INJECT_FAILED, err, 901);
      send_log(req_id, STAGE_DONE, err, 38442);
    }
    return;
  }

  hwnd = resolve_target_window(req_id);
  if (!hwnd) {
    err = (int32_t)GetLastError();
    if (!err) err = ERROR_NOT_FOUND;
    send_log(req_id, STAGE_MSG_INJECT_FAILED, err, 0);
    send_log(req_id, STAGE_DONE, err, 0);
    return;
  }

  set_status_target(hwnd);
  send_log(req_id, STAGE_TARGET_RESOLVE, 0, 0);
  set_status_target(GetParent(hwnd));
  send_log(req_id, STAGE_TARGET_PARENT, 0, 0);
  set_status_target(hwnd);
  try_activate_target(hwnd, req_id);

  if (delete_backward) {
    g_status_route = IME_ROUTE_KEY;
    send_log(req_id, STAGE_MSG_INJECT_BEGIN, 0, VK_BACK);
    if (g_ime_backend == IME_BACKEND_WM_CHAR) ok = send_wm_virtual_key(hwnd, VK_BACK, L'\b');
    else ok = send_virtual_key(VK_BACK);
    err = ok ? 0 : (int32_t)GetLastError();
    if (!ok && !err) err = ERROR_GEN_FAILURE;
    send_log(req_id, ok ? STAGE_MSG_INJECT_END : STAGE_MSG_INJECT_FAILED, err, VK_BACK);
    send_log(req_id, STAGE_DONE, err, 0);
    return;
  }

  if (g_ime_backend == IME_BACKEND_WM_CHAR) {
    g_status_route = IME_ROUTE_WM_CHAR;
    send_log(req_id, STAGE_MSG_INJECT_BEGIN, 0, wchar_count);
    ok = send_wm_char_text(hwnd, wide, wchar_count, submit);
    err = ok ? 0 : (int32_t)GetLastError();
    if (!ok && !err) err = ERROR_GEN_FAILURE;
    send_log(req_id, ok ? STAGE_MSG_INJECT_END : STAGE_MSG_INJECT_FAILED, err, wchar_count);
    send_log(req_id, STAGE_MSG_INJECT_MODE, 0, IME_ROUTE_WM_CHAR);
    send_log(req_id, STAGE_DONE, err, 0);
    return;
  }

  if (g_ime_backend == IME_BACKEND_UNICODE_INPUT) {
    g_status_route = IME_ROUTE_UNICODE_INPUT;
    send_log(req_id, STAGE_MSG_INJECT_BEGIN, 0, wchar_count);
    ok = send_unicode_input_text(wide, wchar_count, submit);
    err = ok ? 0 : (int32_t)GetLastError();
    if (!ok && !err) err = ERROR_GEN_FAILURE;
    send_log(req_id, ok ? STAGE_MSG_INJECT_END : STAGE_MSG_INJECT_FAILED, err, wchar_count);
    send_log(req_id, STAGE_MSG_INJECT_MODE, 0, IME_ROUTE_UNICODE_INPUT);
    send_log(req_id, STAGE_DONE, err, 0);
    return;
  }

  g_status_route = IME_ROUTE_CLIPBOARD_CTRL_V;

  send_log(req_id, STAGE_CLIP_OPEN_BEGIN, 0, 0);
  if (!OpenClipboard(NULL)) {
    err = (int32_t)GetLastError();
    if (!err) err = ERROR_GEN_FAILURE;
    send_log(req_id, STAGE_CLIP_OPEN_FAIL, err, 0);
    send_log(req_id, STAGE_DONE, err, 0);
    return;
  }
  send_log(req_id, STAGE_CLIP_OPEN_OK, 0, 0);

  old_text = capture_clipboard_unicode_copy(&old_wchars);
  if (!EmptyClipboard() || !set_clipboard_unicode(wide, wchar_count)) {
    err = (int32_t)GetLastError();
    if (!err) err = ERROR_GEN_FAILURE;
    send_log(req_id, STAGE_CLIP_SET_FAIL, err, 0);
    CloseClipboard();
    free(old_text);
    send_log(req_id, STAGE_DONE, err, 0);
    return;
  }
  send_log(req_id, STAGE_CLIP_SET_OK, 0, wchar_count);
  CloseClipboard();

  send_log(req_id, STAGE_PASTE_SENDINPUT_BEGIN, 0, 0);
  if (!send_ctrl_v_input()) {
    err = (int32_t)GetLastError();
    if (!err) err = ERROR_GEN_FAILURE;
    send_log(req_id, STAGE_PASTE_SENDINPUT_FAIL, err, 0);
  } else {
    ok = 1;
    send_log(req_id, STAGE_PASTE_SENDINPUT_OK, 0, 4);
  }
  if (ok && submit && !send_virtual_key(VK_RETURN)) {
    ok = 0;
    err = (int32_t)GetLastError();
    if (!err) err = ERROR_GEN_FAILURE;
    send_log(req_id, STAGE_MSG_INJECT_FAILED, err, VK_RETURN);
  }

  send_log(req_id, STAGE_CLIP_RESTORE_DELAY, 0, 220);
  Sleep(220);

  if (OpenClipboard(NULL)) {
    int restored = 0;
    if (old_text) {
      EmptyClipboard();
      restored = set_clipboard_unicode(old_text, old_wchars);
    } else {
      EmptyClipboard();
      restored = 1;
    }
    send_log(req_id, restored ? STAGE_CLIP_RESTORE_OK : STAGE_CLIP_RESTORE_FAIL,
             restored ? 0 : (int32_t)GetLastError(), old_wchars);
    CloseClipboard();
  } else {
    send_log(req_id, STAGE_CLIP_RESTORE_FAIL, (int32_t)GetLastError(), -1);
  }

  free(old_text);
  send_log(req_id, STAGE_MSG_INJECT_MODE, 0, IME_ROUTE_CLIPBOARD_CTRL_V);
  send_log(req_id, STAGE_DONE, ok ? 0 : err, 0);
}

static SOCKET create_bound_udp_socket(uint16_t port) {
  SOCKET s;
  struct sockaddr_in addr;
  BOOL reuse = TRUE;

  s = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
  if (s == INVALID_SOCKET) return INVALID_SOCKET;

  setsockopt(s, SOL_SOCKET, SO_REUSEADDR, (const char *)&reuse, sizeof(reuse));

  ZeroMemory(&addr, sizeof(addr));
  addr.sin_family = AF_INET;
  addr.sin_port = htons(port);
  addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
  if (bind(s, (const struct sockaddr *)&addr, sizeof(addr)) != 0) {
    closesocket(s);
    return INVALID_SOCKET;
  }
  return s;
}

static void append_quoted_arg(WCHAR *dst, size_t cap, const WCHAR *arg) {
  size_t len;
  if (!dst || !arg || cap < 4) return;
  len = safe_wcsnlen_local(dst, cap);
  if (len + 3 >= cap) return;
  dst[len++] = L'"';
  while (*arg && len + 2 < cap) {
    if (*arg == L'"' || *arg == L'\\') {
      if (len + 3 >= cap) break;
      dst[len++] = L'\\';
    }
    dst[len++] = *arg++;
  }
  if (len + 2 < cap) {
    dst[len++] = L'"';
    dst[len] = L'\0';
  }
}

static int append_param_string(WCHAR *dst, size_t cap, const WCHAR *src) {
  size_t len = safe_wcsnlen_local(dst, cap);
  size_t avail;
  size_t src_len;
  if (!dst || !src || len >= cap) return 0;
  avail = cap - len;
  src_len = safe_wcsnlen_local(src, avail);
  if (src_len + 1 > avail) return 0;
  memcpy(dst + len, src, src_len * sizeof(WCHAR));
  dst[len + src_len] = L'\0';
  return 1;
}

static int is_absolute_or_unc_path(const WCHAR *path) {
  if (!path || !path[0]) return 0;
  if (path[0] == L'\\' || path[0] == L'/') return 1;
  return path[1] == L':' ? 1 : 0;
}

static void resolve_launch_target(const WCHAR *filename, const WCHAR *workdir, WCHAR *out, size_t out_cap) {
  size_t dir_len;
  if (!out || out_cap == 0) return;
  out[0] = L'\0';
  if (!filename || !filename[0]) return;
  if (is_absolute_or_unc_path(filename) || !workdir || !workdir[0]) {
    wcsncpy(out, filename, out_cap - 1);
    out[out_cap - 1] = L'\0';
    return;
  }

  wcsncpy(out, workdir, out_cap - 1);
  out[out_cap - 1] = L'\0';
  dir_len = safe_wcsnlen_local(out, out_cap);
  if (dir_len > 0 && out[dir_len - 1] != L'\\' && out[dir_len - 1] != L'/') {
    if (dir_len + 1 < out_cap) {
      out[dir_len++] = L'\\';
      out[dir_len] = L'\0';
    }
  }
  if (dir_len + safe_wcsnlen_local(filename, out_cap - dir_len) < out_cap) {
    wcsncat(out, filename, out_cap - dir_len - 1);
  }
}

static int utf8_bytes_to_wide(const char *src, int len, WCHAR *dst, int dst_cap) {
  int n;
  if (!src || len < 0 || !dst || dst_cap <= 0) return 0;
  n = MultiByteToWideChar(CP_UTF8, 0, src, len, dst, dst_cap - 1);
  if (n <= 0) {
    n = MultiByteToWideChar(CP_ACP, 0, src, len, dst, dst_cap - 1);
  }
  if (n <= 0) return 0;
  dst[n] = L'\0';
  return n;
}

static int launch_process(const WCHAR *filename, const WCHAR *parameters, const WCHAR *workdir) {
  STARTUPINFOW si;
  PROCESS_INFORMATION pi;
  WCHAR cmdline[MAX_CMD];
  WCHAR resolved[MAX_PATH];
  ZeroMemory(&si, sizeof(si));
  ZeroMemory(&pi, sizeof(pi));
  ZeroMemory(cmdline, sizeof(cmdline));
  ZeroMemory(resolved, sizeof(resolved));
  si.cb = sizeof(si);

  resolve_launch_target(filename, workdir, resolved, MAX_PATH);
  append_quoted_arg(cmdline, MAX_CMD, resolved[0] ? resolved : filename);
  if (parameters && parameters[0]) {
    append_param_string(cmdline, MAX_CMD, L" ");
    append_param_string(cmdline, MAX_CMD, parameters);
  }

  debug_log("launch filename=%S workdir=%S params=%S",
            resolved[0] ? resolved : (filename ? filename : L""),
            workdir ? workdir : L"",
            parameters ? parameters : L"");

  if (!CreateProcessW(NULL, cmdline, NULL, NULL, FALSE, 0, NULL,
                      (workdir && workdir[0]) ? workdir : NULL, &si, &pi)) {
    debug_log("CreateProcessW failed error=%lu filename=%S", GetLastError(),
              resolved[0] ? resolved : (filename ? filename : L""));
    return 0;
  }

  CloseHandle(pi.hThread);
  CloseHandle(pi.hProcess);
  return 1;
}

static int parse_startup_launch_request(startup_request_t *req) {
  LPWSTR *argv;
  int argc;
  int i;
  if (!req) return 0;
  ZeroMemory(req, sizeof(*req));

  argv = CommandLineToArgvW(GetCommandLineW(), &argc);
  if (!argv) return 0;

  for (i = 1; i < argc; ++i) {
    if (_wcsicmp(argv[i], L"/dir") == 0 && i + 2 < argc) {
      const WCHAR *dir = argv[i + 1];
      const WCHAR *target = argv[i + 2];
      int j;
      wcsncpy(req->workdir, dir, MAX_PATH - 1);
      wcsncpy(req->target, target, MAX_PATH - 1);
      req->params[0] = L'\0';
      for (j = i + 3; j < argc; ++j) {
        if (req->params[0]) append_param_string(req->params, MAX_CMD, L" ");
        append_quoted_arg(req->params, MAX_CMD, argv[j]);
      }
      req->valid = 1;
      break;
    }
  }

  LocalFree(argv);
  return req->valid;
}

static int wow64_flag_for_process(HANDLE process) {
  BOOL wow64 = FALSE;
  if (!process) return 0;
  if (!IsWow64Process(process, &wow64)) return 0;
  return wow64 ? 1 : 0;
}

static SIZE_T working_set_for_process(HANDLE process) {
  PROCESS_MEMORY_COUNTERS pmc;
  ZeroMemory(&pmc, sizeof(pmc));
  if (!process) return 0;
  if (!GetProcessMemoryInfo(process, &pmc, sizeof(pmc))) return 0;
  return pmc.WorkingSetSize;
}

static DWORD affinity_mask_for_process(HANDLE process) {
  DWORD_PTR process_mask = 0;
  DWORD_PTR system_mask = 0;
  if (!process) return 0;
  if (!GetProcessAffinityMask(process, &process_mask, &system_mask)) return 0;
  return (DWORD)process_mask;
}

static void write_le16(uint8_t *dst, uint16_t v) {
  dst[0] = (uint8_t)(v & 0xff);
  dst[1] = (uint8_t)((v >> 8) & 0xff);
}

static void write_le32(uint8_t *dst, uint32_t v) {
  dst[0] = (uint8_t)(v & 0xff);
  dst[1] = (uint8_t)((v >> 8) & 0xff);
  dst[2] = (uint8_t)((v >> 16) & 0xff);
  dst[3] = (uint8_t)((v >> 24) & 0xff);
}

static void write_le64(uint8_t *dst, uint64_t v) {
  write_le32(dst, (uint32_t)(v & 0xffffffffu));
  write_le32(dst + 4, (uint32_t)((v >> 32) & 0xffffffffu));
}

static void send_process_info_packet(uint16_t index, uint16_t count, DWORD pid, uint64_t working_set,
                                     DWORD affinity_mask, int wow64_process, const char *name) {
  uint8_t pkt[1 + 4 + 2 + 2 + 4 + 8 + 4 + 1 + 32];
  char name_buf[32];
  size_t n = 0;
  ZeroMemory(pkt, sizeof(pkt));
  ZeroMemory(name_buf, sizeof(name_buf));
  if (name) {
    n = strnlen(name, sizeof(name_buf) - 1);
    memcpy(name_buf, name, n);
  }

  pkt[0] = REQ_GET_PROCESS;
  write_le32(pkt + 1, 0);
  write_le16(pkt + 5, count);
  write_le16(pkt + 7, index);
  write_le32(pkt + 9, pid);
  write_le64(pkt + 13, working_set);
  write_le32(pkt + 21, affinity_mask);
  pkt[25] = (uint8_t)(wow64_process ? 1 : 0);
  memcpy(pkt + 26, name_buf, sizeof(name_buf));
  send_control_packet(pkt, sizeof(pkt));
}

static int enumerate_processes_and_send(void) {
  HANDLE snapshot;
  PROCESSENTRY32W pe;
  uint16_t count = 0;
  uint16_t index = 0;

  snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
  if (snapshot == INVALID_HANDLE_VALUE) {
    send_process_info_packet(0, 0, 0, 0, 0, 0, NULL);
    return 0;
  }

  ZeroMemory(&pe, sizeof(pe));
  pe.dwSize = sizeof(pe);
  if (Process32FirstW(snapshot, &pe)) {
    do {
      ++count;
      pe.dwSize = sizeof(pe);
    } while (Process32NextW(snapshot, &pe));
  }
  CloseHandle(snapshot);

  snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
  if (snapshot == INVALID_HANDLE_VALUE) {
    send_process_info_packet(0, 0, 0, 0, 0, 0, NULL);
    return 0;
  }

  ZeroMemory(&pe, sizeof(pe));
  pe.dwSize = sizeof(pe);
  if (Process32FirstW(snapshot, &pe)) {
    do {
      HANDLE process = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION | PROCESS_VM_READ, FALSE, pe.th32ProcessID);
      char name_utf8[32];
      ZeroMemory(name_utf8, sizeof(name_utf8));
      WideCharToMultiByte(CP_ACP, 0, pe.szExeFile, -1, name_utf8, sizeof(name_utf8), NULL, NULL);
      send_process_info_packet(index, count, pe.th32ProcessID,
                               (uint64_t)working_set_for_process(process),
                               affinity_mask_for_process(process),
                               wow64_flag_for_process(process),
                               name_utf8);
      if (process) CloseHandle(process);
      ++index;
      pe.dwSize = sizeof(pe);
    } while (Process32NextW(snapshot, &pe));
  } else {
    send_process_info_packet(0, 0, 0, 0, 0, 0, NULL);
  }
  CloseHandle(snapshot);
  return 1;
}

static int kill_process_by_name_w(const WCHAR *name) {
  HANDLE snapshot;
  PROCESSENTRY32W pe;
  int killed = 0;
  if (!name || !name[0]) return 0;

  snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
  if (snapshot == INVALID_HANDLE_VALUE) return 0;

  ZeroMemory(&pe, sizeof(pe));
  pe.dwSize = sizeof(pe);
  if (Process32FirstW(snapshot, &pe)) {
    do {
      if (_wcsicmp(pe.szExeFile, name) == 0) {
        HANDLE process = OpenProcess(PROCESS_TERMINATE, FALSE, pe.th32ProcessID);
        if (process) {
          if (TerminateProcess(process, 0)) killed = 1;
          CloseHandle(process);
        }
      }
      pe.dwSize = sizeof(pe);
    } while (Process32NextW(snapshot, &pe));
  }
  CloseHandle(snapshot);
  return killed;
}

static int set_affinity_by_name_w(const WCHAR *name, DWORD affinity_mask) {
  HANDLE snapshot;
  PROCESSENTRY32W pe;
  int changed = 0;
  if (!name || !name[0]) return 0;

  snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
  if (snapshot == INVALID_HANDLE_VALUE) return 0;

  ZeroMemory(&pe, sizeof(pe));
  pe.dwSize = sizeof(pe);
  if (Process32FirstW(snapshot, &pe)) {
    do {
      if (_wcsicmp(pe.szExeFile, name) == 0) {
        HANDLE process = OpenProcess(PROCESS_SET_INFORMATION, FALSE, pe.th32ProcessID);
        if (process) {
          if (SetProcessAffinityMask(process, affinity_mask)) changed = 1;
          CloseHandle(process);
        }
      }
      pe.dwSize = sizeof(pe);
    } while (Process32NextW(snapshot, &pe));
  }
  CloseHandle(snapshot);
  return changed;
}

static void handle_exec_request(const uint8_t *packet, int packet_len) {
  int filename_len;
  int params_len;
  WCHAR filename_w[MAX_PATH];
  WCHAR params_w[MAX_CMD];
  if (packet_len < 13) return;
  filename_len = *(const int32_t *)(packet + 5);
  params_len = *(const int32_t *)(packet + 9);
  if (filename_len < 0 || params_len < 0) return;
  if (13 + filename_len + params_len > packet_len) return;

  ZeroMemory(filename_w, sizeof(filename_w));
  ZeroMemory(params_w, sizeof(params_w));
  if (!utf8_bytes_to_wide((const char *)packet + 13, filename_len, filename_w, MAX_PATH)) return;
  if (params_len > 0 &&
      !utf8_bytes_to_wide((const char *)packet + 13 + filename_len, params_len, params_w, MAX_CMD)) {
    return;
  }

  launch_process(filename_w, params_w, NULL);
}

static void handle_kill_process_request(const uint8_t *packet, int packet_len) {
  int name_len;
  WCHAR name_w[MAX_NAME];
  if (packet_len < 5) return;
  name_len = *(const int32_t *)(packet + 1);
  if (name_len <= 0 || 5 + name_len > packet_len) return;
  ZeroMemory(name_w, sizeof(name_w));
  if (!utf8_bytes_to_wide((const char *)packet + 5, name_len, name_w, MAX_NAME)) return;
  kill_process_by_name_w(name_w);
}

static void handle_set_affinity_request(const uint8_t *packet, int packet_len) {
  DWORD pid;
  DWORD affinity_mask;
  uint8_t name_len;
  WCHAR name_w[MAX_NAME];
  HANDLE process;

  if (packet_len < 14) return;
  pid = *(const uint32_t *)(packet + 5);
  affinity_mask = *(const uint32_t *)(packet + 9);
  name_len = packet[13];
  if (14 + name_len > packet_len) return;

  if (pid != 0) {
    process = OpenProcess(PROCESS_SET_INFORMATION, FALSE, pid);
    if (process) {
      SetProcessAffinityMask(process, affinity_mask);
      CloseHandle(process);
    }
    return;
  }

  if (name_len == 0) return;
  ZeroMemory(name_w, sizeof(name_w));
  if (!utf8_bytes_to_wide((const char *)packet + 14, name_len, name_w, MAX_NAME)) return;
  set_affinity_by_name_w(name_w, affinity_mask);
}

static void handle_control_packet(const uint8_t *packet, int packet_len) {
  uint8_t request_code;
  if (!packet || packet_len <= 0) return;
  request_code = packet[0];
  switch (request_code) {
    case REQ_EXEC:
      handle_exec_request(packet, packet_len);
      break;
    case REQ_KILL_PROCESS:
      handle_kill_process_request(packet, packet_len);
      break;
    case REQ_LIST_PROCESSES:
      enumerate_processes_and_send();
      break;
    case REQ_SET_PROCESS_AFFINITY:
      handle_set_affinity_request(packet, packet_len);
      break;
    case REQ_EXIT:
      ExitProcess(0);
      break;
    default:
      break;
  }
}

int WINAPI wWinMain(HINSTANCE hInstance, HINSTANCE hPrevInstance, PWSTR lpCmdLine, int nShowCmd) {
  WSADATA wsa;
  startup_request_t startup_req;
  fd_set readfds;
  struct timeval tv;
  char recv_buf[MAX_PACKET];
  int maxfd = 0;
  int open_url_result;

  (void)hInstance;
  (void)hPrevInstance;
  (void)lpCmdLine;
  (void)nShowCmd;

  if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0) return 1;

  ZeroMemory(&g_host_addr, sizeof(g_host_addr));
  g_host_addr.sin_family = AF_INET;
  g_host_addr.sin_port = htons(HOST_PORT);
  g_host_addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);

  open_url_result = try_send_open_url_command();
  if (open_url_result >= 0) {
    WSACleanup();
    return open_url_result;
  }

  load_ime_backend();
  debug_log("IME backend=%d (1=wm_char,2=unicode,3=clipboard,4=bridge-diagnostic)", g_ime_backend);

  ZeroMemory(&g_bridge_addr, sizeof(g_bridge_addr));
  g_bridge_addr.sin_family = AF_INET;
  g_bridge_addr.sin_port = htons(BRIDGE_PORT);
  g_bridge_addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);

  g_control_socket = create_bound_udp_socket(CONTROL_PORT);
  g_control_mode = g_control_socket != INVALID_SOCKET;
  if (!g_control_mode) {
    debug_log("control port %d busy; falling back to ime-only mode error=%lu", CONTROL_PORT, WSAGetLastError());
  }

  g_ime_socket = create_bound_udp_socket(IME_PORT);
  if (g_ime_socket == INVALID_SOCKET) {
    debug_log("ime port bind failed error=%lu", WSAGetLastError());
    if (!g_control_mode) {
      WSACleanup();
      return 2;
    }
  }

  if (g_control_mode) {
    send_init();
    parse_startup_launch_request(&startup_req);
    debug_log("control mode enabled startup_valid=%d target=%S workdir=%S",
              startup_req.valid, startup_req.target, startup_req.workdir);
  } else {
    ZeroMemory(&startup_req, sizeof(startup_req));
  }

  if (g_ime_socket != INVALID_SOCKET) send_ready();
  if (g_control_mode && startup_req.valid) {
    launch_process(startup_req.target, startup_req.params, startup_req.workdir);
  }

  for (;;) {
    int rc;
    FD_ZERO(&readfds);
    maxfd = 0;
    if (g_control_socket != INVALID_SOCKET) {
      FD_SET(g_control_socket, &readfds);
      if ((int)g_control_socket > maxfd) maxfd = (int)g_control_socket;
    }
    if (g_ime_socket != INVALID_SOCKET) {
      FD_SET(g_ime_socket, &readfds);
      if ((int)g_ime_socket > maxfd) maxfd = (int)g_ime_socket;
    }

    tv.tv_sec = 0;
    tv.tv_usec = 200000;
    rc = select(maxfd + 1, &readfds, NULL, NULL, &tv);
    if (rc == SOCKET_ERROR) {
      Sleep(20);
      continue;
    }
    if (rc == 0) continue;

    if (g_control_socket != INVALID_SOCKET && FD_ISSET(g_control_socket, &readfds)) {
      int len = recv(g_control_socket, recv_buf, sizeof(recv_buf), 0);
      if (len > 0) handle_control_packet((const uint8_t *)recv_buf, len);
    }

    if (g_ime_socket != INVALID_SOCKET && FD_ISSET(g_ime_socket, &readfds)) {
      int len = recv(g_ime_socket, recv_buf, sizeof(recv_buf), 0);
      if (len <= 0) continue;
      if (recv_buf[0] == '{') {
        if (len >= (int)sizeof(recv_buf)) len = (int)sizeof(recv_buf) - 1;
        recv_buf[len] = '\0';
        handle_bridge_json_packet(recv_buf, len);
      } else {
        handle_text_request(recv_buf, len);
      }
    }
  }

  return 0;
}
