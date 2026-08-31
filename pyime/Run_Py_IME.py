
import ctypes
import ctypes.wintypes as wt
import os
import queue
import subprocess
import sys
import threading
import time

from dotenv import load_dotenv

# ---------------------------------------------------------------- 配置
_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))

# 从 pyime 目录下的 .env 文件加载配置
load_dotenv(os.path.join(_SCRIPT_DIR, ".env"))

# 词库文件路径:优先取 .env 中的 DICT_FILE,缺省则用默认文件名;
# 相对路径相对于 pyime 目录解析,绝对路径直接使用
_dict_file = os.environ.get("DICT_FILE", "pinyin_simp.dict.yaml")
if not os.path.isabs(_dict_file):
    _dict_file = os.path.join(_SCRIPT_DIR, _dict_file)
DICT_FILE = _dict_file
PAGE_SIZE = 7   # 每页候选数
MAX_CANDS = 10**9    # 最多取多少个候选(设为极大值=不限制,词库匹配到的词全部进入候选)
MAX_PINYIN = 30      # 拼音缓冲区上限

# 模糊音:每对双向等价;不想要的删掉/注释掉即可,清空列表则完全关闭
FUZZY_PAIRS = [
    ("z", "zh"), ("c", "ch"), ("s", "sh"),    # 平翘舌
     ("en", "eng"), ("in", "ing"), ("on", "ong"),   # on/ong:zon->zong、con->cong 等(zon 等本非合法拼音,自定义)
    ("l", "r"),("n", "l")            # 按需开启
]
MAX_FUZZY_KEYS = 24  # 一次查询最多展开的模糊拼音组合数

ENABLE_SHOUPIN = True  # 简拼:每个音节只打声母,如 zg->这个/中国、bj->北京、nh->你好
MAX_SHOUPIN_PER_KEY = 10**9  # 每个声母串保留多少候选(按词频),极大值=不限制,全部保留

ENABLE_SINGLE_INITIAL = True  # 单字母:打一个字母即列出所有该拼音首字母开头的词,按权重
MAX_SINGLE_INITIAL_PER_KEY = 10**9  # 每个首字母保留多少候选(按词频),极大值=不限制,全部保留

ENABLE_TAIL_INITIAL = True  # 末字简拼:前面音节全拼、最后一个字只打声母,如 sej->设计、jisuanj->计算机
MAX_TAIL_INITIAL_PER_KEY = 10**9  # 每个「前缀+末声母」键保留多少候选(按词频),极大值=不限制,全部保留

user32 = ctypes.windll.user32
kernel32 = ctypes.windll.kernel32

# ---------------------------------------------------------------- Win32 常量
WH_KEYBOARD_LL = 13
WM_KEYDOWN, WM_KEYUP = 0x0100, 0x0101
WM_SYSKEYDOWN, WM_SYSKEYUP = 0x0104, 0x0105
LLKHF_INJECTED = 0x10
KEYEVENTF_KEYUP, KEYEVENTF_UNICODE = 0x0002, 0x0004
INPUT_KEYBOARD = 1
MAGIC_EXTRA = 0x50594D45  # 'PYME':标记自己注入的按键,避免钩子自我循环

VK_BACK, VK_RETURN, VK_SHIFT, VK_CONTROL, VK_MENU = 0x08, 0x0D, 0x10, 0x11, 0x12
VK_CAPITAL = 0x14  # Caps Lock
VK_ESCAPE, VK_SPACE, VK_PRIOR, VK_NEXT = 0x1B, 0x20, 0x21, 0x22
VK_LWIN, VK_RWIN = 0x5B, 0x5C
VK_LSHIFT, VK_RSHIFT = 0xA0, 0xA1
# 低级钩子上报的是区分左右的键码(0xA0/0xA1),不是通用的 VK_SHIFT
SHIFT_KEYS = (VK_SHIFT, VK_LSHIFT, VK_RSHIFT)
VK_OEM_1, VK_OEM_PLUS, VK_OEM_COMMA, VK_OEM_MINUS = 0xBA, 0xBB, 0xBC, 0xBD
VK_OEM_PERIOD, VK_OEM_2, VK_OEM_3 = 0xBE, 0xBF, 0xC0
VK_OEM_4, VK_OEM_5, VK_OEM_6, VK_OEM_7 = 0xDB, 0xDC, 0xDD, 0xDE

ULONG_PTR = ctypes.c_size_t
LRESULT = ctypes.c_ssize_t


class KBDLLHOOKSTRUCT(ctypes.Structure):
    _fields_ = [("vkCode", wt.DWORD), ("scanCode", wt.DWORD),
                ("flags", wt.DWORD), ("time", wt.DWORD),
                ("dwExtraInfo", ULONG_PTR)]


class KEYBDINPUT(ctypes.Structure):
    _fields_ = [("wVk", wt.WORD), ("wScan", wt.WORD), ("dwFlags", wt.DWORD),
                ("time", wt.DWORD), ("dwExtraInfo", ULONG_PTR)]


class MOUSEINPUT(ctypes.Structure):
    _fields_ = [("dx", wt.LONG), ("dy", wt.LONG), ("mouseData", wt.DWORD),
                ("dwFlags", wt.DWORD), ("time", wt.DWORD),
                ("dwExtraInfo", ULONG_PTR)]


class _INPUTunion(ctypes.Union):
    _fields_ = [("ki", KEYBDINPUT), ("mi", MOUSEINPUT)]


class INPUT(ctypes.Structure):
    _fields_ = [("type", wt.DWORD), ("u", _INPUTunion)]


class GUITHREADINFO(ctypes.Structure):
    _fields_ = [("cbSize", wt.DWORD), ("flags", wt.DWORD),
                ("hwndActive", wt.HWND), ("hwndFocus", wt.HWND),
                ("hwndCapture", wt.HWND), ("hwndMenuOwner", wt.HWND),
                ("hwndMoveSize", wt.HWND), ("hwndCaret", wt.HWND),
                ("rcCaret", wt.RECT)]


HOOKPROC = ctypes.WINFUNCTYPE(LRESULT, ctypes.c_int, wt.WPARAM, wt.LPARAM)
user32.SetWindowsHookExW.restype = wt.HHOOK
user32.SetWindowsHookExW.argtypes = (ctypes.c_int, HOOKPROC, wt.HINSTANCE, wt.DWORD)
user32.CallNextHookEx.restype = LRESULT
user32.CallNextHookEx.argtypes = (wt.HHOOK, ctypes.c_int, wt.WPARAM, wt.LPARAM)
user32.SendInput.argtypes = (wt.UINT, ctypes.POINTER(INPUT), ctypes.c_int)
kernel32.GetModuleHandleW.restype = wt.HMODULE
kernel32.GetModuleHandleW.argtypes = (wt.LPCWSTR,)
# 句柄相关函数:必须声明类型,否则 64 位句柄被默认的 c_int 截断 → 取插入符失败
user32.GetForegroundWindow.restype = wt.HWND
user32.GetWindowThreadProcessId.restype = wt.DWORD
user32.GetWindowThreadProcessId.argtypes = (wt.HWND, ctypes.POINTER(wt.DWORD))
user32.GetGUIThreadInfo.argtypes = (wt.DWORD, ctypes.POINTER(GUITHREADINFO))
user32.ClientToScreen.argtypes = (wt.HWND, ctypes.POINTER(wt.POINT))
user32.GetWindowRect.argtypes = (wt.HWND, ctypes.POINTER(wt.RECT))
user32.SetWindowPos.argtypes = (wt.HWND, wt.HWND, ctypes.c_int, ctypes.c_int,
                                ctypes.c_int, ctypes.c_int, wt.UINT)


def shift_down():
    return bool(user32.GetAsyncKeyState(VK_SHIFT) & 0x8000)


def ctrl_down():
    return bool(user32.GetAsyncKeyState(VK_CONTROL) & 0x8000)


def alt_down():
    return bool(user32.GetAsyncKeyState(VK_MENU) & 0x8000)


def win_down():
    return bool((user32.GetAsyncKeyState(VK_LWIN) | user32.GetAsyncKeyState(VK_RWIN)) & 0x8000)


kernel32.OpenProcess.restype = wt.HANDLE
kernel32.OpenProcess.argtypes = (wt.DWORD, wt.BOOL, wt.DWORD)
kernel32.QueryFullProcessImageNameW.argtypes = (wt.HANDLE, wt.DWORD, wt.LPWSTR,
                                                ctypes.POINTER(wt.DWORD))
kernel32.CloseHandle.argtypes = (wt.HANDLE,)

# Win 搜索/开始菜单等沉浸式 shell 面板:系统把它们放在更高的窗口层级(z-band),
# 普通置顶窗口(候选框)画不到它们上面 → 改把候选框挪到面板矩形旁边的空隙里显示
_SHELL_PANEL_EXE = {"searchhost.exe", "searchapp.exe",          # Win11 / Win10 搜索
                    "startmenuexperiencehost.exe",              # 开始菜单
                    "shellexperiencehost.exe"}                  # 通知中心等 shell 面板
_panel_cache = {}  # pid -> bool


def fg_shell_panel():
    """前台窗口属于候选框画不上去的系统沉浸式面板时返回 True。"""
    try:
        hwnd = user32.GetForegroundWindow()
        if not hwnd:
            return False
        pid = wt.DWORD()
        user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
        pid = pid.value
        if pid in _panel_cache:
            return _panel_cache[pid]
        name = ""
        h = kernel32.OpenProcess(0x1000, False, pid)  # PROCESS_QUERY_LIMITED_INFORMATION
        if h:
            buf = ctypes.create_unicode_buffer(260)
            size = wt.DWORD(260)
            if kernel32.QueryFullProcessImageNameW(h, 0, buf, ctypes.byref(size)):
                name = buf.value.rsplit("\\", 1)[-1].lower()
            kernel32.CloseHandle(h)
        ok = name in _SHELL_PANEL_EXE
        if len(_panel_cache) > 256:
            _panel_cache.clear()
        _panel_cache[pid] = ok
        return ok
    except Exception:
        return False


def shell_panel_rect():
    """前台是沉浸式面板时返回其屏幕矩形 (left, top, right, bottom),否则 None。"""
    if not fg_shell_panel():
        return None
    try:
        hwnd = user32.GetForegroundWindow()
        rc = wt.RECT()
        if hwnd and user32.GetWindowRect(hwnd, ctypes.byref(rc)):
            return rc.left, rc.top, rc.right, rc.bottom
    except Exception:
        pass
    return None


def caps_on():
    """系统大小写锁定当前是否打开(Caps 灯亮)。"""
    return bool(user32.GetKeyState(VK_CAPITAL) & 1)


def send_vk(vk):
    """注入一次虚拟键的按下+抬起(带 MAGIC_EXTRA,自己的钩子会忽略)。"""
    arr = (INPUT * 2)()
    for i, flags in enumerate((0, KEYEVENTF_KEYUP)):
        arr[i].type = INPUT_KEYBOARD
        arr[i].u.ki = KEYBDINPUT(vk, 0, flags, 0, MAGIC_EXTRA)
    return user32.SendInput(len(arr), arr, ctypes.sizeof(INPUT))


def send_text(text):
    """用 KEYEVENTF_UNICODE 把字符串发送到当前焦点窗口。"""
    data = text.encode("utf-16-le")
    units = [int.from_bytes(data[i:i + 2], "little") for i in range(0, len(data), 2)]
    arr = (INPUT * (len(units) * 2))()
    i = 0
    for code in units:
        for flags in (KEYEVENTF_UNICODE, KEYEVENTF_UNICODE | KEYEVENTF_KEYUP):
            arr[i].type = INPUT_KEYBOARD
            arr[i].u.ki = KEYBDINPUT(0, code, flags, 0, MAGIC_EXTRA)
            i += 1
    return user32.SendInput(len(arr), arr, ctypes.sizeof(INPUT))


# ---------------------------------------------------------------- UI Automation 取插入符
# Chrome/Edge/Electron/VS Code/Cursor 等 Chromium 内核程序没有经典 Win32 插入符,
# 只能通过 UI Automation 问"当前焦点元素"的屏幕矩形,把候选框贴到它下方。
ole32 = ctypes.windll.ole32
oleaut32 = ctypes.windll.oleaut32
# 必须声明:否则 64 位 SAFEARRAY 指针被默认 c_int 截断 → OverflowError → 取位置失败
oleaut32.SafeArrayAccessData.argtypes = (ctypes.c_void_p, ctypes.POINTER(ctypes.c_void_p))
oleaut32.SafeArrayAccessData.restype = ctypes.c_long
oleaut32.SafeArrayUnaccessData.argtypes = (ctypes.c_void_p,)
oleaut32.SafeArrayUnaccessData.restype = ctypes.c_long
oleaut32.SafeArrayGetUBound.argtypes = (ctypes.c_void_p, ctypes.c_uint,
                                        ctypes.POINTER(ctypes.c_long))
oleaut32.SafeArrayGetUBound.restype = ctypes.c_long
oleaut32.SafeArrayDestroy.argtypes = (ctypes.c_void_p,)
oleaut32.SafeArrayDestroy.restype = ctypes.c_long
oleaut32.VariantClear.argtypes = (ctypes.c_void_p,)
oleaut32.VariantClear.restype = ctypes.c_long
_COINIT_APARTMENTTHREADED = 0x2
_CLSCTX_INPROC_SERVER = 1
_VT_ARRAY_R8 = 0x2005  # VT_ARRAY | VT_R8
_UIA_BoundingRectanglePropertyId = 30001
_UIA_TextPatternId = 10014
_UIA_TextPattern2Id = 10024
_TextUnit_Character = 0
_CLSID_CUIAutomation = "{ff48dba4-60ef-4201-aa87-54103eef594e}"
_IID_IUIAutomation = "{30cbe57d-d9d0-452a-ab13-7ac5ac4825ee}"
_IID_IUIAutomation2 = "{34723aff-0c9d-49d0-9896-7ab52df8cd8a}"
_IID_IUIAutomationTextPattern = "{32eba289-3583-42c9-9c59-3b6d9a1e9b6a}"
_IID_IUIAutomationTextPattern2 = "{506a921a-fcc9-409f-b23b-37eb74106872}"


class _GUID(ctypes.Structure):
    _fields_ = [("Data1", wt.DWORD), ("Data2", wt.WORD), ("Data3", wt.WORD),
                ("Data4", ctypes.c_ubyte * 8)]


class _VARIANT(ctypes.Structure):  # 只取到 union 第一个指针成员就够用
    _fields_ = [("vt", wt.WORD), ("r1", wt.WORD), ("r2", wt.WORD),
                ("r3", wt.WORD), ("parray", ctypes.c_void_p)]


def _guid(s):
    g = _GUID()
    ctypes.oledll.ole32.IIDFromString(s, ctypes.byref(g))
    return g


def _com_call(ptr, index, restype, *args):
    """调用 COM 对象 vtable 第 index 个方法。args 为 (argtype, value) 元组列表。"""
    vtbl = ctypes.cast(ptr, ctypes.POINTER(ctypes.c_void_p))[0]
    fn = ctypes.cast(vtbl, ctypes.POINTER(ctypes.c_void_p))[index]
    proto = ctypes.WINFUNCTYPE(restype, ctypes.c_void_p, *[a[0] for a in args])
    return proto(fn)(ptr, *[a[1] for a in args])


_uia = None         # IUIAutomation*(c_void_p)
_uia_tried = False  # 是否已尝试初始化(失败也只试一次)


def _uia_init():
    global _uia, _uia_tried
    _uia_tried = True
    try:
        ole32.CoInitializeEx(None, _COINIT_APARTMENTTHREADED)
        p = ctypes.c_void_p()
        clsid, iid = _guid(_CLSID_CUIAutomation), _guid(_IID_IUIAutomation)
        ctypes.oledll.ole32.CoCreateInstance(
            ctypes.byref(clsid), None, _CLSCTX_INPROC_SERVER,
            ctypes.byref(iid), ctypes.byref(p))
        _uia = p
        try:
            # IUIAutomation2(Win8+):缩短跨进程调用超时。个别程序(如有道词典
            # 的 Chromium 界面)UIA 响应极慢,不限时会把调用线程卡住数秒
            iid2 = _guid(_IID_IUIAutomation2)
            p2 = ctypes.c_void_p()
            hr = _com_call(p, 0, ctypes.c_long,  # IUnknown::QueryInterface
                           (ctypes.POINTER(_GUID), ctypes.byref(iid2)),
                           (ctypes.POINTER(ctypes.c_void_p), ctypes.byref(p2)))
            if hr == 0 and p2.value:
                # IUIAutomation2::put_ConnectionTimeout —— vtable[61](毫秒)
                _com_call(p2, 61, ctypes.c_long, (wt.DWORD, 1000))
                # IUIAutomation2::put_TransactionTimeout —— vtable[63](毫秒)
                _com_call(p2, 63, ctypes.c_long, (wt.DWORD, 1000))
                _com_call(p2, 2, ctypes.c_ulong)  # Release
        except Exception:
            pass
        print("[PyIME] UI Automation 已就绪(支持 Chrome/Electron 等程序光标跟随)")
    except Exception as e:
        _uia = None
        print("[PyIME] UI Automation 不可用,退回鼠标定位:", e)


def _range_rect(rng):
    """IUIAutomationTextRange::GetBoundingRectangles 的第一个矩形
    (left, top, width, height),没有矩形返回 None。"""
    psa = ctypes.c_void_p()
    # IUIAutomationTextRange::GetBoundingRectangles —— vtable[10]
    hr = _com_call(rng, 10, ctypes.c_long,
                   (ctypes.POINTER(ctypes.c_void_p), ctypes.byref(psa)))
    if hr != 0 or not psa.value:
        return None
    try:
        ub = ctypes.c_long(-1)
        if oleaut32.SafeArrayGetUBound(psa, 1, ctypes.byref(ub)) != 0 or ub.value < 3:
            return None  # 退化(空)区间没有矩形
        data = ctypes.c_void_p()
        if oleaut32.SafeArrayAccessData(psa, ctypes.byref(data)) != 0:
            return None
        d = ctypes.cast(data, ctypes.POINTER(ctypes.c_double))
        rect = (d[0], d[1], d[2], d[3])
        oleaut32.SafeArrayUnaccessData(psa)
        return rect if (rect[2] > 0 or rect[3] > 0) else None
    finally:
        oleaut32.SafeArrayDestroy(psa)


def _caret_from_range(rng):
    """文本区间 → 候选框落点;空区间先扩展到一个字符再取矩形。"""
    rect = _range_rect(rng)
    if rect is None:
        # IUIAutomationTextRange::ExpandToEnclosingUnit —— vtable[6]
        _com_call(rng, 6, ctypes.c_long, (ctypes.c_long, _TextUnit_Character))
        rect = _range_rect(rng)
    if rect:
        left, top, _w, h = rect
        return int(left), int(top + h) + 2
    return None


def _uia_text_caret(elem):
    """焦点元素的文本光标矩形 → (x, y),取不到返回 None。
    PowerShell / cmd(Windows Terminal、conhost)等终端没有经典插入符,
    焦点元素矩形又是整个窗口,只能用 TextPattern 拿真正的光标位置。"""
    iid2 = _guid(_IID_IUIAutomationTextPattern2)
    pat = ctypes.c_void_p()
    # IUIAutomationElement::GetCurrentPatternAs —— vtable[14]
    hr = _com_call(elem, 14, ctypes.c_long,
                   (ctypes.c_long, _UIA_TextPattern2Id),
                   (ctypes.POINTER(_GUID), ctypes.byref(iid2)),
                   (ctypes.POINTER(ctypes.c_void_p), ctypes.byref(pat)))
    if hr == 0 and pat.value:
        try:
            active = wt.BOOL()
            rng = ctypes.c_void_p()
            # IUIAutomationTextPattern2::GetCaretRange —— vtable[10]
            hr = _com_call(pat, 10, ctypes.c_long,
                           (ctypes.POINTER(wt.BOOL), ctypes.byref(active)),
                           (ctypes.POINTER(ctypes.c_void_p), ctypes.byref(rng)))
            if hr == 0 and rng.value:
                try:
                    pos = _caret_from_range(rng)
                    if pos:
                        return pos
                finally:
                    _com_call(rng, 2, ctypes.c_ulong)  # Release
        finally:
            _com_call(pat, 2, ctypes.c_ulong)  # Release
    # 不支持 TextPattern2 时退而求其次:无选区时 GetSelection 返回光标处的空区间
    iid1 = _guid(_IID_IUIAutomationTextPattern)
    pat = ctypes.c_void_p()
    hr = _com_call(elem, 14, ctypes.c_long,
                   (ctypes.c_long, _UIA_TextPatternId),
                   (ctypes.POINTER(_GUID), ctypes.byref(iid1)),
                   (ctypes.POINTER(ctypes.c_void_p), ctypes.byref(pat)))
    if hr != 0 or not pat.value:
        return None
    try:
        arr = ctypes.c_void_p()
        # IUIAutomationTextPattern::GetSelection —— vtable[5]
        hr = _com_call(pat, 5, ctypes.c_long,
                       (ctypes.POINTER(ctypes.c_void_p), ctypes.byref(arr)))
        if hr != 0 or not arr.value:
            return None
        try:
            n = ctypes.c_int(0)
            # IUIAutomationTextRangeArray::get_Length —— vtable[3]
            _com_call(arr, 3, ctypes.c_long,
                      (ctypes.POINTER(ctypes.c_int), ctypes.byref(n)))
            if n.value < 1:
                return None
            rng = ctypes.c_void_p()
            # IUIAutomationTextRangeArray::GetElement —— vtable[4]
            hr = _com_call(arr, 4, ctypes.c_long,
                           (ctypes.c_int, 0),
                           (ctypes.POINTER(ctypes.c_void_p), ctypes.byref(rng)))
            if hr != 0 or not rng.value:
                return None
            try:
                return _caret_from_range(rng)
            finally:
                _com_call(rng, 2, ctypes.c_ulong)  # Release
        finally:
            _com_call(arr, 2, ctypes.c_ulong)  # Release
    finally:
        _com_call(pat, 2, ctypes.c_ulong)  # Release


def uia_caret():
    """用 UIA 取候选框落点 (x, y):先 TextPattern 取真实文本光标
    (终端/编辑器),再退回焦点元素矩形(普通输入框);取不到返回 None。"""
    if not _uia_tried:
        _uia_init()
    if not _uia:
        return None
    elem = ctypes.c_void_p()
    try:
        # IUIAutomation::GetFocusedElement —— vtable[8]
        hr = _com_call(_uia, 8, ctypes.c_long,
                       (ctypes.POINTER(ctypes.c_void_p), ctypes.byref(elem)))
        if hr != 0 or not elem.value:
            return None
        pos = _uia_text_caret(elem)  # 终端/编辑器:真实文本光标
        if pos:
            return pos
        # IUIAutomationElement::GetCurrentPropertyValue —— vtable[10]
        var = _VARIANT()
        hr = _com_call(elem, 10, ctypes.c_long,
                       (ctypes.c_long, _UIA_BoundingRectanglePropertyId),
                       (ctypes.POINTER(_VARIANT), ctypes.byref(var)))
        rect = None
        if hr == 0 and var.vt == _VT_ARRAY_R8 and var.parray:
            data = ctypes.c_void_p()
            if oleaut32.SafeArrayAccessData(var.parray, ctypes.byref(data)) == 0:
                d = ctypes.cast(data, ctypes.POINTER(ctypes.c_double))
                rect = (d[0], d[1], d[2], d[3])  # left, top, width, height
                oleaut32.SafeArrayUnaccessData(var.parray)
        oleaut32.VariantClear(ctypes.byref(var))
        if rect and (rect[2] > 0 or rect[3] > 0):
            left, top, _w, h = rect
            # 拿不到真实光标时(如有道词典的 Chromium 页面),焦点元素往往是
            # 整个文档/窗口,矩形底边就是窗口底部,毫无定位意义;
            # 只有看起来像单行输入框的小矩形才可信,否则退回鼠标位置
            if h > 100:
                return None
            return int(left), int(top + h) + 2
        return None
    except Exception:
        return None
    finally:
        if elem.value:
            _com_call(elem, 2, ctypes.c_ulong)  # IUnknown::Release


def caret_pos():
    """取当前焦点窗口插入符(文本光标)屏幕坐标。
    经典插入符 → UI Automation 焦点元素 → 实在取不到才退回鼠标位置。"""
    try:
        hwnd = user32.GetForegroundWindow()
        tid = user32.GetWindowThreadProcessId(hwnd, None)
        gui = GUITHREADINFO()
        gui.cbSize = ctypes.sizeof(GUITHREADINFO)
        if user32.GetGUIThreadInfo(tid, ctypes.byref(gui)) and gui.hwndCaret:
            # 经典插入符:记事本(经典版)、Win32 编辑框、多数原生 IDE
            pt = wt.POINT(gui.rcCaret.left, gui.rcCaret.bottom)
            user32.ClientToScreen(gui.hwndCaret, ctypes.byref(pt))
            if pt.x or pt.y:
                return pt.x, pt.y + 4
    except Exception:
        pass
    try:
        pos = uia_caret()  # Chromium/Electron/UWP 等:UIA 焦点元素
        if pos:
            return pos
    except Exception:
        pass
    pt = wt.POINT()
    user32.GetCursorPos(ctypes.byref(pt))
    return pt.x + 10, pt.y + 16


class MONITORINFO(ctypes.Structure):
    _fields_ = [("cbSize", wt.DWORD), ("rcMonitor", wt.RECT),
                ("rcWork", wt.RECT), ("dwFlags", wt.DWORD)]


user32.MonitorFromPoint.restype = ctypes.c_void_p
user32.MonitorFromPoint.argtypes = (wt.POINT, wt.DWORD)
user32.GetMonitorInfoW.argtypes = (ctypes.c_void_p, ctypes.POINTER(MONITORINFO))


def work_area(x, y):
    """点 (x, y) 所在显示器的工作区(屏幕去掉任务栏),返回 (left, top, right, bottom);
    取不到时退回主屏全尺寸。"""
    try:
        mon = user32.MonitorFromPoint(wt.POINT(x, y), 2)  # MONITOR_DEFAULTTONEAREST
        mi = MONITORINFO()
        mi.cbSize = ctypes.sizeof(MONITORINFO)
        if mon and user32.GetMonitorInfoW(mon, ctypes.byref(mi)):
            r = mi.rcWork
            return r.left, r.top, r.right, r.bottom
    except Exception:
        pass
    return 0, 0, user32.GetSystemMetrics(0), user32.GetSystemMetrics(1)


# ---------------------------------------------------------------- 词库
_INITIALS_2 = ("zh", "ch", "sh")
_INITIALS_1 = frozenset("bpmfdtnlgkhjqxrzcsyw")
# 简拼里允许出现的字母:声母 + 零声母音节(an/ou/e… 等)的首字母 a/e/o,
# 否则像 安卓=an zhuo→az、欧盟=ou meng→om 这类首音节零声母的词,简拼会查不到
_ABBR_LETTERS = _INITIALS_1 | frozenset("aeo")


def _initial(s):
    """取音节(或不完整音节)的声母,无声母返回空串。"""
    if s[:2] in _INITIALS_2:
        return s[:2]
    return s[0] if s[:1] in _INITIALS_1 else ""


class Dict:
    def __init__(self, path):
        self.table = {}        # "ni hao" -> [(词, 权重), ...] 按权重降序
        self.syllables = set() # 所有合法音节
        raw = open(path, encoding="utf-8").read()
        body = raw.split("\n...", 1)[1]
        for line in body.splitlines():
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 2 or not parts[0] or parts[0].startswith("#"):
                continue
            word, py = parts[0], parts[1].strip()
            try:
                weight = int(parts[2]) if len(parts) > 2 else 0
            except ValueError:
                weight = 0
            self.table.setdefault(py, []).append((word, weight))
            for s in py.split(" "):
                self.syllables.add(s)
        for v in self.table.values():
            v.sort(key=lambda x: -x[1])
        self.maxsyl = max(len(s) for s in self.syllables)
        # 整词优先索引:去掉空格的拼音串 -> [拼音键,...]。用于切分时优先选
        # 「整串恰好拼成词库里某个词」的方案,如 dange-> dan ge=单个,而非贪心的 dang e
        self.concat = {}
        for py in self.table:
            self.concat.setdefault(py.replace(" ", ""), []).append(py)
        # 模糊音:声母替换对 + 每个音节的等价音节集合(闭包,只保留词库里存在的)
        self.init_subs, final_subs = [], []
        for a, b in FUZZY_PAIRS:
            for x, y in ((a, b), (b, a)):
                if x in _INITIALS_1 or x in _INITIALS_2:
                    self.init_subs.append((x, y))
                else:
                    final_subs.append((x, y))
        # 模糊音闭包:不再只保留词库里已存在的等价音节,生成出来的「虚拟音节」
        # (如 ting→tin、ding→din)也一并纳入。这样 segment 能把 tinz 切成 tin+z,
        # 再经模糊键 tin→ting 命中「停止」等词;否则 tin 不被识别会被切成 ti'n'z。
        self.fuzzy = {}  # 音节 -> 等价音节列表(不含自身)
        virtual = set()  # 词库里不存在、仅由模糊音生成的音节
        for s in list(self.syllables):
            group, todo = {s}, [s]
            while todo:
                cur = todo.pop()
                # len(cur) > len(x):声母之外还得有韵母才做声母替换,否则像叹词
                # 音节 "n" 会被 n->l 替成光杆 "l"(再 l->r 成 "r"),这些非法的单字母
                # 「虚拟音节」会污染 self.syllables,使单字母 l/r 被当成完整音节,
                # 跳过「单字母首字母展开」,导致输入 l 出不来「了/来/里」等(只剩 唔/嗯)
                vs = [y + cur[len(x):] for x, y in self.init_subs
                      if _initial(cur) == x and len(cur) > len(x)]
                vs += [cur[:-len(x)] + y for x, y in final_subs if cur.endswith(x)]
                for v in vs:
                    if v and v not in group:
                        group.add(v)
                        todo.append(v)
            # 闭包是等价类,类内每个成员(含虚拟音节)都登记一份模糊映射
            for member in group:
                if member not in self.syllables:
                    virtual.add(member)
                if len(group) > 1:
                    self.fuzzy[member] = sorted(group - {member})
        self.syllables |= virtual
        if virtual:
            self.maxsyl = max(self.maxsyl, max(len(s) for s in virtual))
        # 简拼字母模糊闭包:只取 FUZZY_PAIRS 里两端都是单字母声母的对(如 l/r、n/l;
        # z/zh、s/sh 这类简拼本就取首字符,两端已是同一个字母,无需再处理),构成
        # 字母级等价闭包,供简拼(snt/srt/slt 应等价命中"水龙头")按字母展开查找用。
        self.letter_fuzzy = {}  # 简拼声母字母 -> 模糊等价字母列表(不含自身)
        letter_pairs = [(a, b) for a, b in FUZZY_PAIRS if len(a) == 1 and len(b) == 1]
        letter_alphabet = set()
        for a, b in letter_pairs:
            letter_alphabet.add(a)
            letter_alphabet.add(b)
        for c in letter_alphabet:
            group, todo = {c}, [c]
            while todo:
                cur = todo.pop()
                for a, b in letter_pairs:
                    for x, y in ((a, b), (b, a)):
                        if cur == x and y not in group:
                            group.add(y)
                            todo.append(y)
            if len(group) > 1:
                self.letter_fuzzy[c] = sorted(group - {c})
        # 简拼索引:声母串(每音节取首字母)-> [(词, 权重)],按词频降序、截断
        self.abbr = {}
        if ENABLE_SHOUPIN:
            for py, words in self.table.items():
                sylls = py.split(" ")
                if len(sylls) < 2:
                    continue  # 单字简拼只有一个字母,太歧义,不收
                key = "".join(s[0] for s in sylls)
                self.abbr.setdefault(key, []).extend(words)
            for v in self.abbr.values():
                v.sort(key=lambda x: -x[1])
                del v[MAX_SHOUPIN_PER_KEY:]
        # 单字母索引:拼音首字母 -> [(词, 权重)],按词频降序、截断。
        # 用 py[0] 归桶,所以 "lao shi"=老师 也归在 'l' 下,zh/ch/sh 归在 z/c/s 下
        self.initial_idx = {}
        if ENABLE_SINGLE_INITIAL:
            for py, words in self.table.items():
                c = py[0]
                if c in _ABBR_LETTERS:
                    self.initial_idx.setdefault(c, []).extend(words)
            for v in self.initial_idx.values():
                v.sort(key=lambda x: -x[1])
                del v[MAX_SINGLE_INITIAL_PER_KEY:]
        # 部分简拼索引:前 p 个字全拼 + 末尾若干字只打声母。键=(前缀全拼, 末尾声母串)。
        # 如 你好吗 ni hao ma -> ("ni","hm") 和 ("ni hao","m");设计 she ji -> ("she","j")
        self.part_abbr = {}
        if ENABLE_TAIL_INITIAL:
            for py, words in self.table.items():
                sylls = py.split(" ")
                n = len(sylls)
                if n < 2:
                    continue
                for p in range(1, n):  # 前 p 个全拼,后 n-p 个取声母
                    inits = "".join(s[0] for s in sylls[p:])
                    if not all(c in _INITIALS_1 for c in inits):
                        continue  # 末尾有零声母音节(a/e/o 开头)时不能用声母简拼
                    key = (" ".join(sylls[:p]), inits)
                    self.part_abbr.setdefault(key, []).extend(words)
            for v in self.part_abbr.values():
                v.sort(key=lambda x: -x[1])
                del v[MAX_TAIL_INITIAL_PER_KEY:]

    def _table_seg(self, buf):
        """整词优先:整串字母恰好拼成词库里某个词时,返回该词的音节切分,否则 None。
        如 dange-> dan ge(单个)而非贪心的 dang e。多个词匹配时取音节数最少、
        且首音节尽量长的(与贪心一致);隔音符 ' 必须落在音节边界上。"""
        letters = buf.replace("'", "")
        keys = self.concat.get(letters)
        if not keys:
            return None
        forced, pos = set(), 0  # 隔音符在 letters 中强制要求的音节边界
        for ch in buf:
            if ch == "'":
                forced.add(pos)
            else:
                pos += 1
        forced -= {0, len(letters)}
        best = None
        for k in keys:
            sylls = k.split(" ")
            bounds, acc = set(), 0
            for s in sylls[:-1]:
                acc += len(s)
                bounds.add(acc)
            if not (forced <= bounds):
                continue  # 有隔音符落在音节中间,此切分不符
            rank = (len(sylls), tuple(-len(s) for s in sylls))
            if best is None or rank < best[0]:
                best = (rank, sylls)
        return best[1] if best else None

    def segment(self, buf):
        """把字母串切分成音节列表;优先让整串都被合法音节覆盖(全局匹配),
        如 kangu 切成 kan+gu(看顾)而非贪心的 kang+u(看)。无法整串覆盖时
        退回贪心最长匹配、切不动的字母单独成段。"""
        # 整词优先:整串恰好是词库里某个词时,直接用该词的切分(如 dange->单个)
        ws = self._table_seg(buf)
        if ws is not None:
            return ws
        n = len(buf)
        # can[i]:buf[i:] 能否被合法音节完整覆盖(' 视为透明的音节边界)
        can = [False] * (n + 1)
        can[n] = True
        for i in range(n - 1, -1, -1):
            if buf[i] == "'":
                can[i] = can[i + 1]
                continue
            for L in range(1, min(self.maxsyl, n - i) + 1):
                if can[i + L] and buf[i:i + L] in self.syllables:
                    can[i] = True
                    break
        segs, i = [], 0
        if can[0]:
            # 整串可完整切分:仍最长优先,但只选不会让剩余部分无解的音节
            while i < n:
                if buf[i] == "'":
                    i += 1
                    continue
                for L in range(min(self.maxsyl, n - i), 0, -1):
                    if can[i + L] and buf[i:i + L] in self.syllables:
                        segs.append(buf[i:i + L])
                        i += L
                        break
            return segs
        # 无法整串覆盖:退回原贪心(切不动的字母单独成段)
        while i < n:
            if buf[i] == "'":
                i += 1
                continue
            for L in range(min(self.maxsyl, n - i), 0, -1):
                if buf[i:i + L] in self.syllables:
                    segs.append(buf[i:i + L])
                    i += L
                    break
            else:
                segs.append(buf[i])
                i += 1
        return segs

    def fuzzy_keys(self, sub):
        """完整音节列表 sub 的所有模糊音组合键,第一个固定是原拼音。"""
        combos = [""]
        for s in sub:
            vs = [s] + self.fuzzy.get(s, [])
            combos = [(c + " " + v if c else v) for c in combos for v in vs]
            if len(combos) > MAX_FUZZY_KEYS:
                combos = combos[:MAX_FUZZY_KEYS]
        return combos

    def abbr_keys(self, letters):
        """简拼字母串 letters 的所有模糊音字母组合键,第一个固定是原字母串。"""
        combos = [""]
        for ch in letters:
            vs = [ch] + self.letter_fuzzy.get(ch, [])
            combos = [c + v for c in combos for v in vs]
            if len(combos) > MAX_FUZZY_KEYS:
                combos = combos[:MAX_FUZZY_KEYS]
        return combos

    def _full_segs(self, letters, cap=24):
        """枚举字母串的所有「整串音节切分」方案,按贪心偏好排序(音节数少优先、
        靠前音节长优先;首个即与 segment 的贪心结果一致)。无法整串覆盖时返回 []。
        用于首选切分查不到候选时,回退到另一种有候选的切分(如 yingai 的 ying ai
        无候选时改用 yin gai)。"""
        n = len(letters)
        can = [False] * (n + 1)  # can[i]:letters[i:] 能否被合法音节完整覆盖
        can[n] = True
        for i in range(n - 1, -1, -1):
            for L in range(1, min(self.maxsyl, n - i) + 1):
                if can[i + L] and letters[i:i + L] in self.syllables:
                    can[i] = True
                    break
        if not can[0]:
            return []
        results = []

        def dfs(i, acc):
            if len(results) >= cap:
                return
            if i == n:
                results.append(list(acc))
                return
            for L in range(min(self.maxsyl, n - i), 0, -1):  # 最长优先
                if can[i + L] and letters[i:i + L] in self.syllables:
                    acc.append(letters[i:i + L])
                    dfs(i + L, acc)
                    acc.pop()

        dfs(0, [])
        results.sort(key=lambda ss: (len(ss), tuple(-len(s) for s in ss)))
        return results

    def _seg_has_cands(self, sub):
        """该音节切分整串(精确或模糊键)是否能查到候选词。"""
        return any(self.table.get(k) for k in self.fuzzy_keys(sub))

    def _best_segment(self, buf):
        """优先用 segment 的贪心切分;若它能整串覆盖却查不到任何候选,则在其它
        整串切分里挑第一个有候选的(实现「组合无候选时自动换有候选的组合」)。"""
        primary = self.segment(buf)
        if not primary or not all(s in self.syllables for s in primary):
            return primary  # 无切分或非整串覆盖:保持原贪心行为
        if self._seg_has_cands(primary):
            return primary
        for ss in self._full_segs(buf.replace("'", "")):
            if ss != primary and self._seg_has_cands(ss):
                return ss
        return primary

    def candidates(self, buf):
        """返回 [(候选词, 消耗的音节数)];整串匹配时精确与模糊拼音的候选合并,
        统一按权重排序(同权重精确在前),再逐级缩短;只匹配完整音节,不做前缀补全。"""
        segs = self._best_segment(buf)
        if not segs:
            return [], []
        out, seen = [], set()

        def add(word, nseg, weight):
            if word not in seen and len(out) < MAX_CANDS:
                seen.add(word)
                out.append((word, nseg, weight))

        # 单字母:列出所有该拼音首字母开头的词,纯按权重(initial_idx 已排好序)。
        # 只出单字:输入单个拼音字母时只给单字候选,不带出多字词/缩写
        # (如 s 只出「是、上、说…」,不出「设计、上海」等)。
        # 所有字母一视同仁:即便该字母本身也是个完整音节(a/e/o 零声母,m/n 叹词),
        # 也走首字母展开——initial_idx 已含拼音正好等于该字母的单字(如 a 含啊/阿、
        # m 含呣),按权重排序自然靠前,不会丢;同时把同首字母其它单字一并列出。
        letters0 = buf.replace("'", "")
        if len(letters0) == 1 and letters0 in self.initial_idx:
            for w, _wt in self.initial_idx[letters0]:
                if len(w) == 1:
                    add(w, len(segs), _wt)
            return [(w, n) for w, n, _ in out], segs

        for n in range(len(segs), 0, -1):
            sub = segs[:n]
            complete = all(s in self.syllables for s in sub)
            if complete:
                pool = []  # 精确 + 模糊全部候选,统一按权重排序,同权重精确在前
                for ki, k in enumerate(self.fuzzy_keys(sub)):
                    for w, wt_ in self.table.get(k, []):
                        pool.append((w, wt_, ki))
                pool.sort(key=lambda x: (-x[1], x[2]))
                for w, _wt, _ki in pool:
                    add(w, n, _wt)
            # 整串完整匹配之后、逐级缩短之前,补「部分简拼」候选(消耗整个缓冲区):
            # 前面若干字全拼 + 末尾若干字只打声母,如 sej=设计、nihm=你好吗。
            # 与整串完整匹配同级(都吃满 len(segs)),排在其后、短前缀匹配之前
            if n == len(segs):
                self._add_part_abbr(buf, len(segs), add)

        # 简拼:整串全是声母字母时,按声母串(含字母级模糊音展开)补充候选(消耗整个缓冲区)
        letters = buf.replace("'", "")
        if (self.abbr and len(letters) >= 2
                and all(c in _ABBR_LETTERS for c in letters)):
            abbr_pool = []
            for ki, k in enumerate(self.abbr_keys(letters)):
                for w, wt_ in self.abbr.get(k, []):
                    abbr_pool.append((w, wt_, ki))
            abbr_pool.sort(key=lambda x: (-x[1], x[2]))
            for w, _wt, _ki in abbr_pool:
                add(w, len(segs), _wt)
        # 消耗输入更多的候选排在前(如 nbn:吃满 3 个字母的"能不能"应在只占 1 个的"嗯"前);
        # 消耗数相同时按权重降序(如 az:简拼"安卓"应在末字简拼"阿紫"前);
        # 稳定排序,权重也相同时保持插入次序(精确优先于模糊、全拼优先于简拼)
        out.sort(key=lambda x: (-x[1], -x[2]))
        return [(w, n) for w, n, _ in out], segs

    def _add_part_abbr(self, buf, nseg, add):
        """部分简拼:把末尾 t 个字母当作连续声母、前缀整段切成完整音节去查索引。
        直接在原始字母串上枚举 t(不依赖贪心切分,避免 nihm 里 hm 被当成音节)。"""
        if not self.part_abbr:
            return
        letters = buf.replace("'", "")
        pool = []
        for t in range(1, len(letters)):  # 末尾 t 个字母作声母,剩下作全拼前缀
            tail = letters[-t:]
            if not all(c in _INITIALS_1 for c in tail):
                break  # 末尾出现非声母字母,再往左也不可能全是声母
            pre = self.segment(letters[:-t])
            if not all(s in self.syllables for s in pre):
                continue  # 前缀切不成完整音节
            for ki, combo in enumerate(self.fuzzy_keys(pre)):
                for w, wt_ in self.part_abbr.get((combo, tail), []):
                    pool.append((w, wt_, ki + t * 100))  # t 越小(全拼越多)越靠前
        pool.sort(key=lambda x: (-x[1], x[2]))
        for w, _wt, _ki in pool:
            add(w, nseg, _wt)

    def bump(self, word, sub):
        """选词调权:把选中词的权重提为同拼音候选池里的最大权重 + 1(内存立即生效)。
        返回需写回文件的 (拼音键, 新权重);已是唯一最高或找不到时返回 None。"""
        sylls = [s for s in sub if s != "'"]
        if not sylls:
            return None
        target, pool = None, None
        if all(s in self.syllables for s in sylls):
            keys = self.fuzzy_keys(sylls)  # 第一个是原拼音,优先归到精确键下
            for k in keys:
                if any(w == word for w, _ in self.table.get(k, ())):
                    target = k
                    break
            if target:
                pool = [t for k in keys for t in self.table.get(k, ())]
        if target is None:  # 简拼选词:反查该词真正的拼音键(简拼按字母模糊展开)
            letters = "".join(sylls)
            abbr_keylist = self.abbr_keys(letters)
            ab_key = None
            for k in abbr_keylist:
                ab = self.abbr.get(k)
                if ab and any(w == word for w, _ in ab):
                    ab_key = k
                    break
            if ab_key is not None:
                # 比较池要合并该简拼串所有模糊音组合键的桶,而不只是词所在的那一个键:
                # candidates() 里 "ll" 会连 "nl"/"rl" 等模糊变体一起展示、统一按权重排序,
                # 调权时若只跟自己所在键内比较,赢不过其它模糊键里权重更高的词,选完再打
                # 还是排不到最前面。
                pool = [t for k in abbr_keylist for t in self.abbr.get(k, ())]
                for k, v in self.table.items():
                    ss = k.split(" ")
                    if (len(ss) == len(ab_key)
                            and all(s[0] == c for s, c in zip(ss, ab_key))
                            and any(w == word for w, _ in v)):
                        target = k
                        break
        if target is None and len(sylls) == 1 and sylls[0] in self.initial_idx:
            # 单字母选词(如打 s 出「是/说/上」):反查该字真正的拼音键。
            # 单字母不是完整音节(如 s 本身不合法),前两条反查都命中不了,
            # 否则调权直接被跳过——打单字母选词永远排不到前面。
            letter = sylls[0]
            for k, v in self.table.items():
                if " " not in k and k[:1] == letter and any(w == word for w, _ in v):
                    target, pool = k, list(v)
                    break
        if target is None:
            return None
        # 比较池并入「简拼桶」:否则像 yun mu 这种全拼键下只有「韵母」一个词时,
        # 它本就是唯一最高,权重永远涨不上去,进不了简拼 ym 的竞争(始终排在高频词后)。
        # 纳入简拼桶后,选一次就把它提到能在简拼里也排第一。
        cmp_pool = list(pool)
        sylls_t = target.split(" ")
        if len(sylls_t) >= 2:
            ab = self.abbr.get("".join(s[0] for s in sylls_t))
            if ab:
                cmp_pool += ab
        elif len(sylls_t) == 1:
            # 单音节键同理要并入「单字母索引」桶:该桶按拼音首字母把 a/ai/an/ang…
            # 各键的单字全部混在一起展示(见 candidates() 的单字母展开),选词调权
            # 只在 target 自己的键内比较是不够的,选中的词赢不过同屏其它键来的词。
            idx = self.initial_idx.get(target[0])
            if idx:
                cmp_pool += idx
        mx = max(w_ for _, w_ in cmp_pool)
        cur = next(w_ for w, w_ in self.table[target] if w == word)
        if cur == mx and sum(1 for _, w_ in cmp_pool if w_ == mx) == 1:
            return None  # 已是唯一最高,无需调整
        new = mx + 1
        self._set_weight(target, word, new)
        return target, new

    def _set_weight(self, key, word, weight):
        """把 word 在所有相关索引里的权重统一改为 weight 并重排,保证各处排序一致:
        主拼音表、简拼桶、末字简拼、单字母索引(它们各持一份 (词,权重) 副本)。"""
        def upd(lst):
            if not lst:
                return
            for i, (w, _) in enumerate(lst):
                if w == word:
                    lst[i] = (w, weight)
                    lst.sort(key=lambda x: -x[1])
                    return
        upd(self.table.get(key))
        sylls = key.split(" ")
        if len(sylls) >= 2:
            upd(self.abbr.get("".join(s[0] for s in sylls)))  # 简拼桶
            for p in range(1, len(sylls)):                    # 末字简拼各切分点
                inits = "".join(s[0] for s in sylls[p:])
                upd(self.part_abbr.get((" ".join(sylls[:p]), inits)))
        upd(self.initial_idx.get(key[0]))                     # 单字母索引(按拼音首字母归桶)


# ---------------------------------------------------------------- 输入法状态机(运行在钩子线程)
class Engine:
    PUNCT = {  # 组词中按标点用(英文半角):vk -> (普通, Shift)
        VK_OEM_COMMA: (",", "<"), VK_OEM_PERIOD: (".", ">"),
        VK_OEM_1: (";", ":"), VK_OEM_2: ("/", "?"),
        VK_OEM_5: ("\\", "|"), VK_OEM_3: ("`", "~"),
        VK_OEM_4: ("[", "{"), VK_OEM_6: ("]", "}"),
        VK_OEM_MINUS: ("-", "_"), VK_OEM_PLUS: ("=", "+"),
        VK_OEM_7: ("'", '"'),
        0x31: (None, "!"), 0x34: (None, "$"), 0x36: (None, "^"),
        0x39: (None, "("), 0x30: (None, ")"),
    }

    def __init__(self, dic, ui_q):
        self.dic = dic
        self.q = ui_q
        self.cn_mode = True
        self.caps_mode = False  # 系统大小写锁定是否打开(仅用于托盘显示 A/英)
        self.buf = ""
        self.cands = []      # [(词, 消耗音节数)]
        self.segs = []
        self.page = 0
        self.shift_tap = False  # Shift 按下且尚无其他键 → 抬起时切换模式
        self._caps_eaten = False  # 本次 Caps 的 keydown 是否被吞掉(keyup 要跟着吞)
        self.posgen = 0      # 定位代数:上屏/清空后 +1,UI 线程据此重新取候选框落点
        self.tray = None     # 系统托盘图标(托盘创建后回填),用于切换时刷新 中/英

    # ---------- UI 通知 ----------
    def refresh(self):
        if not self.buf:
            self.q.put(("hide",))
            return
        self.cands, self.segs = self.dic.candidates(self.buf)
        self.page = 0
        self.push_ui()

    def push_ui(self):
        total = max(1, (len(self.cands) + PAGE_SIZE - 1) // PAGE_SIZE)
        self.page = max(0, min(self.page, total - 1))
        items = self.cands[self.page * PAGE_SIZE:(self.page + 1) * PAGE_SIZE]
        disp = ["%d.%s" % (i + 1, w) for i, (w, _n) in enumerate(items)]
        # 定位放到 UI 线程做:UIA 跨进程调用可能阻塞几百毫秒(如有道词典的
        # Chromium 界面),在钩子线程里做会超时,按键被系统直接放行
        self.q.put(("show", "'".join(self.segs), disp, self.page + 1, total, self.posgen))

    def commit(self, text):
        n = send_text(text)
        self.posgen += 1  # 上屏后目标光标移动了,下次重新定位
        print("[PyIME] 上屏 %r,SendInput=%d" % (text, n))

    def clear(self):
        self.buf = ""
        self.cands = []
        self.posgen += 1
        self.q.put(("hide",))

    def toggle(self):
        self.cn_mode = not self.cn_mode
        if self.cn_mode and caps_on():
            send_vk(VK_CAPITAL)  # 切回中文时关掉大写锁定,避免状态串台
        self.caps_mode = False
        self.clear()
        if self.tray:  # 状态显示在系统托盘图标上(中/英),不再屏幕弹窗
            self.tray.set_mode(self.cn_mode)
        print("[PyIME] %s" % ("中文模式" if self.cn_mode else "英文模式"))

    def caps_to_english(self):
        """Caps Lock 在中文下:切到英文,并真正打开系统大小写锁定(Caps 灯亮)。"""
        if self.buf:  # 组词中按 Caps:已输入的原始字母先上屏(同 Shift 单击)
            self.commit(self.buf.replace("'", ""))
        self.cn_mode = False
        self.clear()
        if not caps_on():
            send_vk(VK_CAPITAL)
        self.caps_mode = True
        if self.tray:
            self.tray.set_mode(self.cn_mode)
        print("[PyIME] 英文大写模式")

    # ---------- 候选选择 ----------
    def choose(self, idx):
        flat = self.page * PAGE_SIZE + idx
        if flat >= len(self.cands):
            return
        word, nseg = self.cands[flat]
        self.commit(word)
        try:  # 选词调权:提到同拼音最高,并排队写回词库文件
            upd = self.dic.bump(word, self.segs[:nseg])
            if upd:
                _weight_q.put((word,) + upd)
        except Exception as e:
            print("[PyIME] 选词调权失败:%s" % e)
        # 去掉已消耗音节对应的字母(buf 里可能夹隔音符),余下继续组词
        b = self.buf
        for s in self.segs[:nseg]:
            b = b.lstrip("'")
            if b.startswith(s):
                b = b[len(s):]
        self.buf = b.lstrip("'")
        if self.buf:
            self.refresh()
        else:
            self.clear()

    # ---------- 按键处理:返回 True 表示吞掉该键 ----------
    def on_key_down(self, vk):
        if vk not in SHIFT_KEYS:
            self.shift_tap = False

        # 全局热键
        if vk == VK_SPACE and ctrl_down():
            self.toggle()
            return True
        if vk == VK_CAPITAL:
            if self.cn_mode:  # 中文 → 英文大写,自己接管(不让 Caps 灯在中文下乱亮)
                self.caps_to_english()
                self._caps_eaten = True
                return True
            # 英文模式下 Caps Lock 交还系统:只翻转大小写锁定,不切回中文
            self._caps_eaten = False
            return False
        if vk in SHIFT_KEYS:
            if not (ctrl_down() or alt_down() or win_down()):
                self.shift_tap = True
            return False

        if not self.cn_mode:
            return False  # 英文模式全部放行,大小写由系统的 Caps Lock 决定
        if ctrl_down() or alt_down() or win_down():
            return False  # 带修饰键的快捷键直接放行,组词不受影响(取消用 Esc)

        composing = bool(self.buf)

        # 字母
        if 0x41 <= vk <= 0x5A:
            if shift_down() and not composing:
                return False  # Shift+字母 → 直接当英文大写
            if len(self.buf) < MAX_PINYIN:
                self.buf += chr(vk + 32)
                self.refresh()
            return True

        if composing:
            if 0x31 <= vk <= 0x39 or 0x61 <= vk <= 0x69:  # 1-9 / 小键盘1-9
                if shift_down() and 0x31 <= vk <= 0x39:
                    pass  # 落到标点逻辑
                else:
                    self.choose((vk & 0x0F) - 1)
                    return True
            if vk == VK_SPACE:
                self.choose(0)
                return True
            if vk == VK_BACK:
                self.buf = self.buf[:-1]
                self.refresh()
                return True
            if vk == VK_ESCAPE:
                self.clear()
                return True
            if vk == VK_RETURN:
                self.commit(self.buf.replace("'", ""))
                self.clear()
                return True
            if vk in (VK_OEM_MINUS, VK_PRIOR) and not shift_down():
                self.page -= 1
                self.push_ui()
                return True
            if vk in (VK_OEM_PLUS, VK_NEXT) and not shift_down():
                self.page += 1
                self.push_ui()
                return True
            if vk == VK_OEM_7 and not shift_down():  # ' 隔音符
                if self.buf and not self.buf.endswith("'"):
                    self.buf += "'"
                    self.refresh()
                return True

        # 标点(组词中按标点 = 先上屏首选词再发标点;不在组词直接放行)
        ch = self.punct_char(vk)
        if ch is None or not composing:
            return False
        self.choose(0)
        self.commit(ch)
        return True

    def punct_char(self, vk):
        pair = self.PUNCT.get(vk)
        if not pair:
            return None
        return pair[1] if shift_down() else pair[0]

    def on_key_up(self, vk):
        if vk == VK_CAPITAL:
            if self._caps_eaten:  # keydown 已接管,抬起一并吞掉
                self._caps_eaten = False
                return True
            self.caps_mode = not self.caps_mode  # 系统刚翻转了大写锁定,同步托盘 A/英
            if self.tray:
                self.tray.set_mode(self.cn_mode)
            return False
        if vk in SHIFT_KEYS and self.shift_tap:
            self.shift_tap = False
            if self.buf:  # 组词中单击 Shift:原始字母上屏并取消候选,同时切回英文模式
                self.commit(self.buf.replace("'", ""))
                self.clear()
                if self.cn_mode:
                    self.toggle()  # 上屏后从中文切到英文模式
            else:
                self.toggle()
        return False


# ---------------------------------------------------------------- 系统托盘图标
shell32 = ctypes.windll.shell32
gdi32 = ctypes.windll.gdi32

WM_USER = 0x0400
WM_TRAYICON = WM_USER + 1
NIM_ADD, NIM_MODIFY, NIM_DELETE = 0, 1, 2
NIF_MESSAGE, NIF_ICON, NIF_TIP = 0x01, 0x02, 0x04
WM_LBUTTONUP, WM_LBUTTONDBLCLK, WM_RBUTTONUP = 0x0202, 0x0203, 0x0205
MF_STRING, MF_SEPARATOR = 0x0000, 0x0800
TPM_RIGHTBUTTON, TPM_RETURNCMD = 0x0002, 0x0100
SM_CXSMICON, SM_CYSMICON = 49, 50

WNDPROC = ctypes.WINFUNCTYPE(LRESULT, wt.HWND, wt.UINT, wt.WPARAM, wt.LPARAM)


class WNDCLASSW(ctypes.Structure):
    _fields_ = [("style", wt.UINT), ("lpfnWndProc", WNDPROC),
                ("cbClsExtra", ctypes.c_int), ("cbWndExtra", ctypes.c_int),
                ("hInstance", wt.HINSTANCE), ("hIcon", wt.HICON),
                ("hCursor", wt.HANDLE), ("hbrBackground", wt.HANDLE),
                ("lpszMenuName", wt.LPCWSTR), ("lpszClassName", wt.LPCWSTR)]


class NOTIFYICONDATAW(ctypes.Structure):
    _fields_ = [("cbSize", wt.DWORD), ("hWnd", wt.HWND), ("uID", wt.UINT),
                ("uFlags", wt.UINT), ("uCallbackMessage", wt.UINT),
                ("hIcon", wt.HICON), ("szTip", wt.WCHAR * 128),
                ("dwState", wt.DWORD), ("dwStateMask", wt.DWORD),
                ("szInfo", wt.WCHAR * 256), ("uVersion", wt.UINT),
                ("szInfoTitle", wt.WCHAR * 64), ("dwInfoFlags", wt.DWORD),
                ("guidItem", _GUID), ("hBalloonIcon", wt.HICON)]


class ICONINFO(ctypes.Structure):
    _fields_ = [("fIcon", wt.BOOL), ("xHotspot", wt.DWORD), ("yHotspot", wt.DWORD),
                ("hbmMask", wt.HBITMAP), ("hbmColor", wt.HBITMAP)]


class BITMAPINFOHEADER(ctypes.Structure):
    _fields_ = [("biSize", wt.DWORD), ("biWidth", wt.LONG), ("biHeight", wt.LONG),
                ("biPlanes", wt.WORD), ("biBitCount", wt.WORD),
                ("biCompression", wt.DWORD), ("biSizeImage", wt.DWORD),
                ("biXPelsPerMeter", wt.LONG), ("biYPelsPerMeter", wt.LONG),
                ("biClrUsed", wt.DWORD), ("biClrImportant", wt.DWORD)]


# 句柄相关:声明类型,避免 64 位句柄/指针被默认 c_int 截断
user32.CreateWindowExW.restype = wt.HWND
user32.CreateWindowExW.argtypes = (
    wt.DWORD, wt.LPCWSTR, wt.LPCWSTR, wt.DWORD,
    ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int,
    ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p)
user32.DefWindowProcW.restype = LRESULT
user32.DefWindowProcW.argtypes = (ctypes.c_void_p, wt.UINT, wt.WPARAM, wt.LPARAM)
user32.RegisterClassW.argtypes = (ctypes.POINTER(WNDCLASSW),)
user32.RegisterClassW.restype = ctypes.c_ushort
user32.GetDC.restype = ctypes.c_void_p
user32.GetDC.argtypes = (ctypes.c_void_p,)
user32.ReleaseDC.argtypes = (ctypes.c_void_p, ctypes.c_void_p)
user32.FillRect.argtypes = (ctypes.c_void_p, ctypes.POINTER(wt.RECT), ctypes.c_void_p)
user32.DrawTextW.argtypes = (ctypes.c_void_p, wt.LPCWSTR, ctypes.c_int,
                             ctypes.POINTER(wt.RECT), wt.UINT)
user32.CreateIconIndirect.restype = ctypes.c_void_p
user32.CreateIconIndirect.argtypes = (ctypes.POINTER(ICONINFO),)
user32.DestroyIcon.argtypes = (ctypes.c_void_p,)
gdi32.CreateCompatibleDC.restype = ctypes.c_void_p
gdi32.CreateCompatibleDC.argtypes = (ctypes.c_void_p,)
gdi32.DeleteDC.argtypes = (ctypes.c_void_p,)
gdi32.CreateBitmap.restype = ctypes.c_void_p
gdi32.CreateBitmap.argtypes = (ctypes.c_int, ctypes.c_int, wt.UINT, wt.UINT, ctypes.c_void_p)
gdi32.CreateDIBSection.restype = ctypes.c_void_p
gdi32.CreateDIBSection.argtypes = (ctypes.c_void_p, ctypes.c_void_p, wt.UINT,
                                   ctypes.POINTER(ctypes.c_void_p), ctypes.c_void_p, wt.DWORD)
gdi32.SelectObject.restype = ctypes.c_void_p
gdi32.SelectObject.argtypes = (ctypes.c_void_p, ctypes.c_void_p)
gdi32.DeleteObject.argtypes = (ctypes.c_void_p,)
gdi32.SetBkMode.argtypes = (ctypes.c_void_p, ctypes.c_int)
gdi32.SetTextColor.restype = wt.COLORREF
gdi32.SetTextColor.argtypes = (ctypes.c_void_p, wt.COLORREF)
gdi32.CreateSolidBrush.restype = ctypes.c_void_p
gdi32.CreateSolidBrush.argtypes = (wt.COLORREF,)
gdi32.CreateFontW.restype = ctypes.c_void_p
gdi32.CreateFontW.argtypes = (ctypes.c_int,) * 5 + (wt.DWORD,) * 8 + (wt.LPCWSTR,)
user32.CreatePopupMenu.restype = wt.HMENU
user32.AppendMenuW.argtypes = (ctypes.c_void_p, wt.UINT, ctypes.c_size_t, wt.LPCWSTR)
user32.TrackPopupMenu.restype = ctypes.c_int
user32.TrackPopupMenu.argtypes = (ctypes.c_void_p, wt.UINT, ctypes.c_int,
    ctypes.c_int, ctypes.c_int, ctypes.c_void_p, ctypes.c_void_p)
user32.SetForegroundWindow.argtypes = (ctypes.c_void_p,)
user32.DestroyMenu.argtypes = (ctypes.c_void_p,)
user32.DestroyWindow.argtypes = (ctypes.c_void_p,)
user32.PostMessageW.argtypes = (ctypes.c_void_p, wt.UINT, wt.WPARAM, wt.LPARAM)
shell32.Shell_NotifyIconW.restype = wt.BOOL
shell32.Shell_NotifyIconW.argtypes = (wt.DWORD, ctypes.POINTER(NOTIFYICONDATAW))


def _rgb(r, g, b):
    return r | (g << 8) | (b << 16)


def make_text_icon(text, bg, fg):
    """用 GDI 现画一个写着 text(如 '中'/'英')的小图标,返回 HICON。"""
    w = user32.GetSystemMetrics(SM_CXSMICON) or 16
    h = user32.GetSystemMetrics(SM_CYSMICON) or 16
    screen = user32.GetDC(None)
    hdc = gdi32.CreateCompatibleDC(screen)
    bmi = BITMAPINFOHEADER()
    bmi.biSize = ctypes.sizeof(BITMAPINFOHEADER)
    bmi.biWidth, bmi.biHeight = w, -h   # 负高 = 自上而下,方便直接定位像素
    bmi.biPlanes, bmi.biBitCount = 1, 32
    bits = ctypes.c_void_p()
    color = gdi32.CreateDIBSection(hdc, ctypes.byref(bmi), 0, ctypes.byref(bits), None, 0)
    mask = gdi32.CreateBitmap(w, h, 1, 1, None)
    old = gdi32.SelectObject(hdc, color)
    rect = wt.RECT(0, 0, w, h)
    brush = gdi32.CreateSolidBrush(bg)
    user32.FillRect(hdc, ctypes.byref(rect), brush)
    gdi32.DeleteObject(brush)
    font = gdi32.CreateFontW(-(h * 4 // 5), 0, 0, 0, 700, 0, 0, 0,
                             1, 0, 0, 4, 0, "Microsoft YaHei")
    oldf = gdi32.SelectObject(hdc, font)
    gdi32.SetBkMode(hdc, 1)             # TRANSPARENT
    gdi32.SetTextColor(hdc, fg)
    user32.DrawTextW(hdc, text, -1, ctypes.byref(rect), 0x25)  # CENTER|VCENTER|SINGLELINE
    gdi32.SelectObject(hdc, oldf)
    gdi32.DeleteObject(font)
    gdi32.SelectObject(hdc, old)
    if bits.value:  # GDI 画完 alpha 通道全 0,强制设满,否则整个图标会透明
        buf = (ctypes.c_ubyte * (w * h * 4)).from_address(bits.value)
        for i in range(3, w * h * 4, 4):
            buf[i] = 255
    ii = ICONINFO(1, 0, 0, mask, color)
    hicon = user32.CreateIconIndirect(ctypes.byref(ii))
    gdi32.DeleteObject(color)
    gdi32.DeleteObject(mask)
    gdi32.DeleteDC(hdc)
    user32.ReleaseDC(None, screen)
    return hicon


class TrayIcon:
    """在钩子线程(已有 Win32 消息循环)里挂一个隐藏窗口承载托盘图标 + 右键菜单。"""
    CLASS_NAME = "PyIME_TrayWnd"
    ID_TOGGLE, ID_RESTART, ID_QUIT = 1, 2, 3

    def __init__(self, engine):
        self.engine = engine
        self.hwnd = None
        self.nid = None
        self.hicon = None
        self._proc = WNDPROC(self._wnd_proc)  # 必须保持引用防止被 GC

    def _mode_text(self):
        if self.engine.cn_mode:
            return "中"
        return "A" if self.engine.caps_mode else "英"

    def _mode_name(self):
        if self.engine.cn_mode:
            return "中文"
        return "英文大写" if self.engine.caps_mode else "英文"

    def _make_icon(self):
        return make_text_icon(self._mode_text(), _rgb(0x1e, 0x6f, 0xd9), _rgb(255, 255, 255))

    def _tip(self):
        return "PyIME — %s(右键菜单 / 双击切换)" % self._mode_name()

    def create(self):
        hinst = kernel32.GetModuleHandleW(None)
        wc = WNDCLASSW()
        wc.lpfnWndProc = self._proc
        wc.hInstance = hinst
        wc.lpszClassName = self.CLASS_NAME
        user32.RegisterClassW(ctypes.byref(wc))
        self.hwnd = user32.CreateWindowExW(0, self.CLASS_NAME, "PyIME", 0,
                                           0, 0, 0, 0, None, None, hinst, None)
        self.hicon = self._make_icon()
        nid = NOTIFYICONDATAW()
        nid.cbSize = ctypes.sizeof(NOTIFYICONDATAW)
        nid.hWnd = self.hwnd
        nid.uID = 1
        nid.uFlags = NIF_MESSAGE | NIF_ICON | NIF_TIP
        nid.uCallbackMessage = WM_TRAYICON
        nid.hIcon = self.hicon
        nid.szTip = self._tip()
        shell32.Shell_NotifyIconW(NIM_ADD, ctypes.byref(nid))
        self.nid = nid
        print("[PyIME] 系统托盘图标已创建")

    def set_mode(self, cn_mode):
        """中/英状态变化时刷新托盘图标和提示文字。"""
        if self.nid is None:
            return
        old = self.hicon
        self.hicon = self._make_icon()
        self.nid.hIcon = self.hicon
        self.nid.szTip = self._tip()
        shell32.Shell_NotifyIconW(NIM_MODIFY, ctypes.byref(self.nid))
        if old:
            user32.DestroyIcon(old)

    def _wnd_proc(self, hwnd, msg, wparam, lparam):
        if msg == WM_TRAYICON:
            low = lparam & 0xFFFF
            if low in (WM_RBUTTONUP, WM_LBUTTONUP):
                self._menu()
            elif low == WM_LBUTTONDBLCLK:
                self.engine.toggle()
            return 0
        return user32.DefWindowProcW(hwnd, msg, wparam, lparam)

    def _menu(self):
        menu = user32.CreatePopupMenu()
        toggle_text = "切换到英文" if self.engine.cn_mode else "切换到中文"
        user32.AppendMenuW(menu, MF_STRING, self.ID_TOGGLE, toggle_text)
        user32.AppendMenuW(menu, MF_SEPARATOR, 0, None)
        user32.AppendMenuW(menu, MF_STRING, self.ID_RESTART, "重启(&R)")
        user32.AppendMenuW(menu, MF_STRING, self.ID_QUIT, "退出(&X)")
        pt = wt.POINT()
        user32.GetCursorPos(ctypes.byref(pt))
        user32.SetForegroundWindow(self.hwnd)  # 否则菜单点外面不消失
        cmd = user32.TrackPopupMenu(menu, TPM_RIGHTBUTTON | TPM_RETURNCMD,
                                    pt.x, pt.y, 0, self.hwnd, None)
        user32.PostMessageW(self.hwnd, 0, 0, 0)  # WM_NULL:修复菜单收起的老 bug
        user32.DestroyMenu(menu)
        if cmd == self.ID_QUIT:
            self.engine.q.put(("quit",))
        elif cmd == self.ID_RESTART:
            self._restart()
        elif cmd == self.ID_TOGGLE:
            self.engine.toggle()

    def _restart(self):
        """拉起一个新的 PyIME 进程,再让当前进程退出。"""
        try:
            args = [sys.executable, os.path.abspath(sys.argv[0])] + sys.argv[1:]
            subprocess.Popen(args, close_fds=True)
            print("[PyIME] 正在重启 ...")
        except Exception as e:
            print("[PyIME] 重启失败:", e)
            return
        self.engine.q.put(("quit",))

    def destroy(self):
        if self.nid is not None:
            shell32.Shell_NotifyIconW(NIM_DELETE, ctypes.byref(self.nid))
            self.nid = None
        if self.hicon:
            user32.DestroyIcon(self.hicon)
            self.hicon = None
        if self.hwnd:
            user32.DestroyWindow(self.hwnd)
            self.hwnd = None


# ---------------------------------------------------------------- 钩子线程
class HookThread(threading.Thread):
    def __init__(self, engine):
        super().__init__(daemon=True)
        self.engine = engine
        self.tid = None
        self._proc = HOOKPROC(self._callback)  # 必须保持引用防止被 GC

    def _callback(self, n_code, w_param, l_param):
        if n_code >= 0:
            kb = ctypes.cast(l_param, ctypes.POINTER(KBDLLHOOKSTRUCT)).contents
            if kb.dwExtraInfo != MAGIC_EXTRA:  # 忽略自己注入的按键
                try:
                    if os.environ.get("PYIME_DEBUG"):
                        print("[PyIME] key wp=%#x vk=%#x flags=%#x" %
                              (w_param, kb.vkCode, kb.flags))
                    if w_param in (WM_KEYDOWN, WM_SYSKEYDOWN):
                        if self.engine.on_key_down(kb.vkCode):
                            return 1
                    elif w_param in (WM_KEYUP, WM_SYSKEYUP):
                        if self.engine.on_key_up(kb.vkCode):
                            return 1
                except Exception as e:
                    print("[PyIME] 钩子异常:", e)
        return user32.CallNextHookEx(None, n_code, w_param, l_param)

    def run(self):
        self.tid = kernel32.GetCurrentThreadId()
        hook = user32.SetWindowsHookExW(WH_KEYBOARD_LL, self._proc,
                                        kernel32.GetModuleHandleW(None), 0)
        if not hook:
            print("[PyIME] 安装键盘钩子失败!GetLastError=%d" % kernel32.GetLastError())
            self.engine.q.put(("quit",))
            return
        tray = TrayIcon(self.engine)
        try:
            tray.create()
            self.engine.tray = tray
        except Exception as e:
            print("[PyIME] 创建系统托盘图标失败:", e)
        msg = wt.MSG()
        while user32.GetMessageW(ctypes.byref(msg), None, 0, 0) > 0:
            user32.TranslateMessage(ctypes.byref(msg))
            user32.DispatchMessageW(ctypes.byref(msg))
        try:
            tray.destroy()
        except Exception:
            pass
        user32.UnhookWindowsHookEx(hook)

    def stop(self):
        if self.tid:
            user32.PostThreadMessageW(self.tid, 0x0012, 0, 0)  # WM_QUIT


# ---------------------------------------------------------------- 界面(主线程)
def run_ui(ui_q, hook_thread):
    import tkinter as tk
    import tkinter.font as tkfont

    root = tk.Tk()
    root.withdraw()

    win = tk.Toplevel(root)
    win.withdraw()
    win.overrideredirect(True)
    win.attributes("-topmost", True)
    BG, FG = "#ffffff", "#202020"
    frame = tk.Frame(win, bg=BG, padx=8, pady=5,
                     highlightthickness=1, highlightbackground="#c0c0c0")
    frame.pack()
    f_comp = tkfont.Font(family="Microsoft YaHei UI", size=10)
    f_cand = tkfont.Font(family="Microsoft YaHei UI", size=12)
    lbl_comp = tk.Label(frame, bg=BG, fg=FG, font=f_comp, anchor="w")
    lbl_comp.pack(fill="x")
    lbl_cand = tk.Label(frame, bg=BG, fg=FG, font=f_cand, anchor="w")
    lbl_cand.pack(fill="x")

    SW_HIDE, SW_SHOWNOACTIVATE = 0, 4
    GWL_EXSTYLE = -20
    WS_EX_NOACTIVATE, WS_EX_TOOLWINDOW, WS_EX_TOPMOST = 0x08000000, 0x80, 0x08
    HWND_TOPMOST = wt.HWND(-1)
    SWP_NOSIZE, SWP_NOMOVE, SWP_NOACTIVATE = 0x0001, 0x0002, 0x0010

    def init_hwnd(w):
        """取窗口句柄并加 WS_EX_NOACTIVATE,显示/隐藏全部走 ShowWindow,
        避免 tk 的 deiconify 抢走目标程序的焦点。"""
        w.geometry("+-10000+-10000")   # 在屏幕外完成首次映射
        w.deiconify()
        w.update_idletasks()
        try:
            hwnd = int(w.wm_frame(), 16) or w.winfo_id()
        except Exception:
            hwnd = w.winfo_id()
        hwnd = wt.HWND(hwnd)
        style = user32.GetWindowLongPtrW(hwnd, GWL_EXSTYLE)
        user32.SetWindowLongPtrW(hwnd, GWL_EXSTYLE,
                                 style | WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW | WS_EX_TOPMOST)
        user32.ShowWindow(hwnd, SW_HIDE)
        return hwnd

    hwnd_win = init_hwnd(win)

    def place_show(w, hwnd, x, y):
        w.update_idletasks()
        ww, wh = w.winfo_reqwidth(), w.winfo_reqheight()
        left, top, right, bottom = work_area(x, y)  # 所在显示器工作区(不含任务栏)
        r = shell_panel_rect()
        if r:  # Win 搜索等沉浸式面板:候选框画不到其上层,挪到面板旁边的空隙里
            if r[0] - left >= ww + 16:     # 左侧空间够
                x = r[0] - ww - 8
            elif right - r[2] >= ww + 16:  # 右侧空间够
                x = r[2] + 8
            elif r[3] + wh + 8 <= bottom:  # 两侧都不够 → 面板下方
                x, y = r[0], r[3] + 8
            else:                          # 最后退路:面板上方
                x, y = r[0], r[1] - wh - 8
        if y + wh > bottom:
            y = y - wh - 28  # 底部放不下(光标贴近任务栏)→ 翻到光标上方,避免遮挡输入行
        x = max(left, min(x, right - ww))
        y = max(top, min(y, bottom - wh))
        w.geometry("+%d+%d" % (x, y))
        w.update_idletasks()
        user32.ShowWindow(hwnd, SW_SHOWNOACTIVATE)
        # 抢到置顶层最上面:只设 WS_EX_TOPMOST 样式位不够,遇到目标程序自身的置顶窗口
        # (如设了 WindowStaysOnTopHint 的对话框)候选框会被压在下面看不见、像打不出字。
        # 每次显示都用 SetWindowPos(HWND_TOPMOST) 重新提到置顶组最上层(不抢焦点)。
        user32.SetWindowPos(hwnd, HWND_TOPMOST, 0, 0, 0, 0,
                            SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE)

    pos_cache = (None, (0, 0))  # (定位代数, 落点):组词期间光标不动,每代只查一次

    def poll():
        nonlocal pos_cache
        try:
            while True:
                msg = ui_q.get_nowait()
                kind = msg[0]
                if kind == "show":
                    _, comp, cands, page, pages, gen = msg
                    lbl_comp.config(text=comp)
                    txt = "  ".join(cands) if cands else "(无候选,回车上屏字母)"
                    if pages > 1:
                        txt += "   %d/%d" % (page, pages)
                    lbl_cand.config(text=txt)
                    if pos_cache[0] != gen:
                        pos_cache = (gen, caret_pos())
                    place_show(win, hwnd_win, *pos_cache[1])
                elif kind == "hide":
                    user32.ShowWindow(hwnd_win, SW_HIDE)
                elif kind == "quit":
                    print("[PyIME] 退出。")
                    hook_thread.stop()
                    root.destroy()
                    return
        except queue.Empty:
            pass
        root.after(25, poll)

    root.after(25, poll)
    root.mainloop()


# ---------------------------------------------------------------- 自测
def selftest(dic):
    assert dic.segment("nihao") == ["ni", "hao"]
    assert dic.segment("zhongguo") == ["zhong", "guo"]
    assert dic.segment("xi'an") == ["xi", "an"]
    cands, _ = dic.candidates("nihao")
    assert cands and cands[0][0] == "你好", cands[:5]
    cands, _ = dic.candidates("zhongguo")
    assert any(w == "中国" for w, _n in cands[:3]), cands[:5]
    cands, _ = dic.candidates("zhon")       # on/ong 模糊:zhon 视作虚拟音节 zhong,能出候选
    assert cands, cands[:8]
    cands, _ = dic.candidates("shurufa")
    assert any(w == "输入法" for w, _n in cands[:5]), cands[:5]
    cands, _ = dic.candidates("women")
    assert cands[0][0] == "我们", cands[:5]
    cands, _ = dic.candidates("nihaoshijie")  # 部分匹配:你好 + 剩余
    assert cands[0] == ("你好", 2), cands[:5]
    # 模糊音
    cands, _ = dic.candidates("zongguo")      # z/zh
    assert any(w == "中国" for w, _n in cands[:5]), cands[:5]
    cands, _ = dic.candidates("longyi")       # l/r:容易
    assert any(w == "容易" for w, _n in cands[:5]), cands[:5]
    cands, _ = dic.candidates("gaoxin")       # in/ing:高兴
    assert any(w == "高兴" for w, _n in cands[:5]), cands[:5]
    cands, _ = dic.candidates("zon")          # on/ong 模糊:zon 视作虚拟音节 zong,能出候选
    assert cands, cands[:8]
    cands, _ = dic.candidates("zhongguo")     # 精确匹配仍排第一
    assert cands[0][0] == "中国", cands[:5]
    # 简拼
    cands, _ = dic.candidates("zg")           # 这个 / 中国
    assert any(w == "这个" for w, _n in cands), [w for w, _ in cands[:10]]
    assert any(w == "中国" for w, _n in cands), [w for w, _ in cands[:10]]
    cands, _ = dic.candidates("bj")           # 北京
    assert any(w == "北京" for w, _n in cands[:10]), [w for w, _ in cands[:10]]
    cands, _ = dic.candidates("nh")           # 你好
    assert any(w == "你好" for w, _n in cands[:10]), [w for w, _ in cands[:10]]
    cands, segs = dic.candidates("zg")        # 选 zg 应消耗整个缓冲区
    assert segs == ["z", "g"], segs
    print("selftest OK —— 词条数:%d,音节数:%d,模糊音节:%d,简拼串:%d"
          % (len(dic.table), len(dic.syllables), len(dic.fuzzy), len(dic.abbr)))


_weight_q = queue.Queue()   # (词, 拼音键, 新权重) 待写回文件
_SELF_WRITE = [0.0]         # 自己写文件产生的 mtime,watch_dict 据此跳过重载


def _update_dict_file(word, key, weight):
    """把词库文件里 word+key 那一行的权重列改为 weight,原子替换写回。"""
    raw = open(DICT_FILE, encoding="utf-8", newline="").read()
    lines = raw.split("\n")
    body = False
    for i, line in enumerate(lines):
        if not body:
            if line.strip() == "...":  # yaml 头结束标记,词条在其后
                body = True
            continue
        parts = line.split("\t")
        if len(parts) >= 2 and parts[0] == word and parts[1].strip() == key:
            if len(parts) >= 3:
                parts[2] = str(weight)
            else:
                parts.append(str(weight))
            lines[i] = "\t".join(parts)
            break
    else:
        return False
    tmp = DICT_FILE + ".tmp"
    with open(tmp, "w", encoding="utf-8", newline="") as f:
        f.write("\n".join(lines))
    os.replace(tmp, DICT_FILE)
    return True


def weight_writer():
    """串行消费 _weight_q,把选词调权写回词库文件(独立线程,不阻塞键盘钩子)。"""
    while True:
        word, key, weight = _weight_q.get()
        try:
            if _update_dict_file(word, key, weight):
                _SELF_WRITE[0] = os.path.getmtime(DICT_FILE)
            else:
                print("[PyIME] 调权未写入:词库文件里找不到 %s\t%s" % (word, key))
        except Exception as e:
            print("[PyIME] 写回词库权重失败:%s" % e)


def watch_dict(engine):
    """后台轮询词库文件的修改时间,变化后热重载词库并替换 engine.dic,
    无需重启应用。加载失败(如文件写到一半)保留旧词库,下次修改再试。"""
    try:
        last = os.path.getmtime(DICT_FILE)
    except OSError:
        last = 0
    while True:
        time.sleep(1)
        try:
            m = os.path.getmtime(DICT_FILE)
        except OSError:
            continue  # 文件正在被替换,暂时不存在
        if m == last:
            continue
        time.sleep(0.5)  # 等编辑器写完
        try:
            if os.path.getmtime(DICT_FILE) != m:
                continue  # 还在写入,下一轮再查
        except OSError:
            continue
        last = m
        if m == _SELF_WRITE[0]:
            continue  # 是自己写回的权重,内存已同步,不必重载
        try:
            t0 = time.time()
            dic = Dict(DICT_FILE)
            engine.dic = dic
            if engine.buf:  # 正在组词则用新词库立即刷新候选
                engine.refresh()
            print("[PyIME] 检测到词库修改,已重新加载:%d 条,耗时 %.1fs"
                  % (len(dic.table), time.time() - t0))
        except Exception as e:
            print("[PyIME] 词库重载失败,继续使用旧词库:%s" % e)


def ensure_admin():
    """若本进程未提权,则以管理员身份重新启动自身(弹 UAC)。

    键盘钩子/SendInput 若要作用于以管理员身份运行的程序(如自带提权的剪贴板管理器、
    Cursor 等),本程序必须同样提权,否则会被 Windows 的 UIPI 机制拦截——
    普通权限进程既钩不到管理员窗口的按键,也无法把中文注入进去。
    """
    if sys.platform != "win32":
        return
    try:
        if ctypes.windll.shell32.IsUserAnAdmin():
            return  # 已是管理员,无需处理
    except Exception:
        return
    try:
        exe = sys.executable
        if getattr(sys, "frozen", False):
            params = subprocess.list2cmdline(sys.argv[1:])          # 打包 exe:重启自身
        else:
            params = subprocess.list2cmdline([os.path.abspath(__file__)] + sys.argv[1:])  # 源码:用解释器重跑本脚本
        # "runas" 触发 UAC 提权;成功则退出当前未提权实例
        ret = ctypes.windll.shell32.ShellExecuteW(None, "runas", exe, params, None, 1)
        if ret > 32:
            sys.exit(0)
    except Exception as e:
        print("[PyIME] 请求管理员权限失败:", e)


def main():
    if "--selftest" not in sys.argv:
        ensure_admin()  # 自动提权,确保能对管理员程序组词/上屏;selftest 不需要

    try:  # Per-Monitor V2 DPI,坐标才能和 tkinter 对得上
        user32.SetProcessDpiAwarenessContext(ctypes.c_ssize_t(-4))
    except Exception:
        try:
            user32.SetProcessDPIAware()
        except Exception:
            pass

    print("[PyIME] 加载词库 ...")
    t0 = time.time()
    dic = Dict(DICT_FILE)
    print("[PyIME] 词库加载完成:%d 条,耗时 %.1fs" % (len(dic.table), time.time() - t0))

    if "--selftest" in sys.argv:
        selftest(dic)
        return

    ui_q = queue.Queue()
    engine = Engine(dic, ui_q)
    hook = HookThread(engine)
    hook.start()
    threading.Thread(target=watch_dict, args=(engine,), daemon=True).start()
    threading.Thread(target=weight_writer, daemon=True).start()
    print("[PyIME] 已启动,当前为中文模式。")
    run_ui(ui_q, hook)


if __name__ == "__main__":
    main()
