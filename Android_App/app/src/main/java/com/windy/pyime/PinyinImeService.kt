package com.windy.pyime

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.ExtractedTextRequest
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink
import com.google.mlkit.vision.digitalink.RecognitionResult
import java.io.File
import java.util.concurrent.Executors

/**
 * 拼音输入法服务。自绘 QWERTY 软键盘 + 候选栏,逻辑参照 PC 版 Engine:
 * 点字母 -> 累积拼音 -> 出候选 -> 点候选/空格上屏并调权(写回词库文件)。
 * 词库固定从 /storage/emulated/0/1/IME_Yaml/D_IME_Yaml/pinyin_simp.dict.yaml 读取(用户手动放置)。
 */
class PinyinImeService : InputMethodService() {

    private val dictFile: File
        get() = File(Environment.getExternalStorageDirectory(), "1/IME_Yaml/D_IME_Yaml/pinyin_simp.dict.yaml")

    @Volatile private var dict: PinyinDict? = null
    @Volatile private var dictMtime: Long = -1L    // 词库文件最后修改时间,键盘弹出时用于判断是否需要重新读取
    private val mainHandler = Handler(Looper.getMainLooper())
    private val writeExec = Executors.newSingleThreadExecutor()

    // 组词状态
    private var buf = ""
    private var cands: List<PinyinDict.Candidate> = emptyList()
    private var segs: List<String> = emptyList()
    private var cnMode = true

    // UI 引用(随 onCreateInputView 重建)
    private var pinyinPreview: TextView? = null
    private var candidatesScroll: HorizontalScrollView? = null
    private var candidatesContainer: LinearLayout? = null
    private var toolbarRow: LinearLayout? = null   // 常驻工具条容器(顶栏 + 展开面板)
    private var keyboardKeysGroup: LinearLayout? = null // 字母/功能键位区(工具条展开时隐藏,腾出空间)
    private var toolbarTopRow: LinearLayout? = null     // 顶部一行:展开按钮 + 前若干个按钮
    private var toolbarExtraPanel: LinearLayout? = null // 展开面板:其余按钮 / 排序编辑
    private var toolbarExpanded = false                 // 展开面板是否打开
    private var toolbarEditing = false                  // 是否处于按钮排序编辑模式
    private var toolOrder: MutableList<String> = mutableListOf()  // 工具按钮顺序(存按钮 id)
    private var modeKey: TextView? = null

    private var rowHeightDp = DEFAULT_ROW_HEIGHT   // 当前键盘行高(可在设置页调节)

    private var shiftState = SHIFT_OFF             // 英文大小写:关 / 单次大写 / 大写锁定
    private var shiftKey: TextView? = null
    private val letterKeys = ArrayList<Pair<Char, TextView>>()  // 字母键引用,大小写变化时刷新

    // 剪贴板/常用语
    private var dataStore: DataStore? = null
    private var keyboardView: View? = null         // 键盘整体
    private var panelView: View? = null            // 工具面板(剪贴板/常用语)
    private var panelContent: LinearLayout? = null // 工具面板内容区(Tab 切换时重建)
    private var panelHeader: LinearLayout? = null  // 工具面板内容区上方的固定栏(如常用语的文件夹行),不随内容滚动
    private var currentFolder: DataStore.Folder? = null  // 常用语当前进入的文件夹(null=最近)

    // 跳转到编辑页(PhraseEditActivity)/同步页(SyncActivity)后,返回时自动重开工具面板
    private var pendingReopenPanel = false
    private var pendingReopenFolderUuid: String? = null
    private var pendingReopenTab = TAB_PHRASE   // 返回后要重开的 Tab(剪贴板/常用语)

    private var symbolView: View? = null           // 数字符号页(九宫格)

    private var symbolPickerView: View? = null     // 符号选择页(主面板里没有的符号)
    private var symbolPickerGrid: LinearLayout? = null  // 符号网格容器(按最近使用排序)
    private var symbolOrder: MutableList<String> = mutableListOf()  // 符号当前排序(最近使用在前)

    // 手写输入(Google ML Kit Digital Ink,首次需联网下载中文模型,之后离线识别)
    private var handwritingView: View? = null       // 手写面板整体
    private var handwritingPad: HandwritingPad? = null  // 画板
    private var hwCandidates: LinearLayout? = null  // 手写候选行
    private var hwStatus: TextView? = null          // 状态提示(下载/识别/出错)
    private var recognizer: DigitalInkRecognizer? = null
    private var modelReady = false                  // 中文手写模型是否已就绪
    private val recognizeRunnable = Runnable { runRecognition() }  // 停笔后防抖触发识别

    // 光标操作面板
    private var cursorView: View? = null           // 光标操作面板(方向键 + 选择/剪切/复制/粘贴)
    private var selectKey: TextView? = null        // 中间「选择」键引用,切换选择模式时刷新外观
    private var selecting = false                  // 选择模式:开时移动光标会扩展选区
    private var curSelStart = 0                    // 当前编辑框选区起点(由 onUpdateSelection 维护)
    private var curSelEnd = 0                      // 当前编辑框选区终点
    private var selAnchor = 0                      // 选择模式锚点:固定不动的一端

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)

    private var builtNight = false   // 上次构建输入视图时的夜间状态(用于检测系统切换后重建)

    /** 当前是否处于系统夜间模式。 */
    private fun isNight(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    // 随夜间模式切换的调色板(强调蓝/红/灰提示色在明暗下通用,不随之变化)
    private fun colPanelBg() = if (isNight()) 0xFF202124.toInt() else 0xFFECEFF1.toInt()
    private fun colSurface() = if (isNight()) 0xFF3C4043.toInt() else Color.WHITE
    private fun colText() = if (isNight()) 0xFFE8EAED.toInt() else 0xFF202020.toInt()

    private val ROW1 = "qwertyuiop"
    private val ROW2 = "asdfghjkl"
    private val ROW3 = "zxcvbnm"

    /** 字母键上滑可输入的符号/数字:点字母打拼音,向上滑动则输入对应字符。 */
    private val swipeSymbols = mapOf(
        // 顶行 qwertyuiop 上滑输入数字 1234567890
        'q' to "1",
        'w' to "2",
        'e' to "3",
        'r' to "4",
        't' to "5",
        'y' to "6",
        'u' to "7",
        'i' to "8",
        'o' to "9",
        'p' to "0",
        // 原顶行符号下移到下两行字母(a→! s→@ 保留)
        'a' to "!",
        's' to "@",
        'd' to "~",
        'f' to "\"",
        'g' to ">",
        'h' to "-",
        'j' to "\\",
        'k' to "*",
        'l' to ";",
        'z' to "'",
        'x' to "/",
        'c' to "_",
        'v' to "+",
        'b' to "<",
        'n' to ":",
        'm' to "&",
    )

    override fun onCreate() {
        super.onCreate()
        dataStore = DataStore(this)
        reloadDictIfChanged()
    }

    /**
     * 若词库文件的修改时间与上次加载时不同,则在后台线程重新读取并重建索引。
     * 键盘每次弹出时都会调用,用户在文件里增删词条后无需手动刷新。
     */
    private fun reloadDictIfChanged() {
        Thread {
            val f = dictFile
            val mtime = if (f.exists()) f.lastModified() else -1L
            // 若上次已成功加载且文件未变化,才跳过;词库未加载成功时每次都重试
            // (清空存储/恢复出厂设置后,文件访问权限会被系统重置,首次读取可能失败,
            // 但文件本身 mtime 不变,不能靠 mtime 相同就永久放弃重试)
            if (mtime == dictMtime && dict != null) return@Thread
            val d = try {
                if (f.exists()) PinyinDict.load(f) else null
            } catch (e: Exception) {
                null
            }
            if (d != null) dictMtime = mtime
            dict = d
            mainHandler.post {
                updatePreview()
                if (buf.isNotEmpty()) refresh()
            }
        }.start()
    }

    // ---------------------------------------------------------------- UI 构建
    override fun onCreateInputView(): View = buildInputView()

    /** 键盘唤起时:若设置页改过高度则重建键盘;复位到键盘视图;采集系统剪贴板。 */
    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        reloadDictIfChanged()
        if (prefs().getInt(KEY_ROW_HEIGHT, DEFAULT_ROW_HEIGHT) != rowHeightDp
            || isNight() != builtNight) {   // 高度改过 或 系统夜间模式切换过 -> 重建
            setInputView(buildInputView())
        }
        // 区分当前输入框是自己的编辑页,还是外部 app 的输入框
        val isOwnEditor = info?.packageName == packageName
        if (pendingReopenPanel && !isOwnEditor) {
            // 从编辑页/同步页返回到外部输入框:重开之前所在的工具面板(并定位到原文件夹)
            pendingReopenPanel = false
            toolTab = pendingReopenTab
            currentFolder = pendingReopenFolderUuid?.let { uuid ->
                dataStore?.folders()?.find { it.uuid == uuid }
            }
            openToolPanel()
        } else {
            // 普通唤起,或刚跳进自己的编辑页:都显示拼音字母主键盘,便于直接输入
            // (进编辑页时保留 pendingReopenPanel,等返回外部 app 再重开面板)
            closeToolPanel()
        }
        captureClipboard()
    }

    /** 读取系统剪贴板当前内容,与最新一条不同则存入历史顶端(输入法已获焦,允许读取)。 */
    private fun captureClipboard() {
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            val clip = cm.primaryClip ?: return
            if (clip.itemCount == 0) return
            val text = clip.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
            if (!text.isNullOrEmpty()) {
                val ds = dataStore
                writeExec.execute { ds?.upsertClipTop(text) }
            }
        } catch (e: Exception) { /* 读剪贴板失败不影响输入 */ }
    }

    private fun buildInputView(): View {
        rowHeightDp = prefs().getInt(KEY_ROW_HEIGHT, DEFAULT_ROW_HEIGHT)
        builtNight = isNight()
        letterKeys.clear()
        currentFolder = null

        // FrameLayout 根容器:键盘与工具面板叠放,通过显隐切换
        val root = FrameLayout(this).apply {
            setBackgroundColor(colPanelBg())
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val kb = buildKeyboardView()
        val panel = buildToolPanel().apply { visibility = View.GONE }
        val cursor = buildCursorPanel().apply { visibility = View.GONE }
        val symbol = buildSymbolView().apply { visibility = View.GONE }
        val symbolPicker = buildSymbolPicker().apply { visibility = View.GONE }
        val handwriting = buildHandwritingPanel().apply { visibility = View.GONE }
        keyboardView = kb
        panelView = panel
        cursorView = cursor
        symbolView = symbol
        symbolPickerView = symbolPicker
        handwritingView = handwriting
        root.addView(kb)
        root.addView(panel)
        root.addView(cursor)
        root.addView(symbol)
        root.addView(symbolPicker)
        root.addView(handwriting)
        return root
    }

    /** 构建键盘整体(拼音预览行 + 候选行 + 三行字母 + 功能行)。 */
    private fun buildKeyboardView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colPanelBg())
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // 拼音预览行(固定高度,无输入时 GONE 不占位)
        pinyinPreview = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#4A90D9"))
            setPadding(dp(12), 0, dp(12), 0)
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(PREVIEW_HEIGHT)
            )
            visibility = View.GONE
        }
        root.addView(pinyinPreview)

        // 候选词横向滚动栏(固定高度 + 垂直居中,空时 INVISIBLE 占位)
        candidatesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        candidatesScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(candidatesContainer)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(CANDIDATES_HEIGHT)
            )
            visibility = View.GONE
        }
        root.addView(candidatesScroll)

        // 常驻工具条:总高 = HEADER_HEIGHT = 预览(PREVIEW_HEIGHT)+候选(CANDIDATES_HEIGHT),与有输入时总高一致,切换时键盘不跳动
        toolbarRow = buildToolbar()
        root.addView(toolbarRow)

        // 键盘:三行字母 + 功能行(整体装进一个容器,工具条展开排序面板时隐藏,避免输入法过高)
        keyboardKeysGroup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(letterRow(ROW1))
            addView(letterRow(ROW2, sideSpacer = 0.5f))
            addView(row3())
            addView(functionRow())
        }
        root.addView(keyboardKeysGroup)

        updatePreview()
        return root
    }

    /** 工具按钮定义:id 用于持久化顺序,icon 是显示图标,label 是排序编辑时的文字说明。 */
    private data class ToolDef(val id: String, val icon: String, val label: String)

    /** 全部可用的工具按钮(顺序仅作为首次使用时的默认排序)。 */
    private val toolDefs = listOf(
        ToolDef("menu", "☰", "展开菜单"),
        ToolDef("symbol", "➕", "符号"),
        ToolDef("clip", "📋", "剪贴板"),
        ToolDef("phrase", "📌", "常用语"),
        ToolDef("paste", "⎘", "粘贴最近"),
        ToolDef("cursor", "✥", "光标"),
        ToolDef("hide", "⌄", "收起键盘"),
        ToolDef("handwriting", "✍", "手写"),
        ToolDef("sync", "☁", "同步"),
        ToolDef("selectall", "🆎", "全选文字"),
        ToolDef("copy", "📄", "复制"),
        ToolDef("clear", "❌", "清除"),
    )

    /** 执行某个工具按钮的动作。 */
    private fun runToolAction(id: String) {
        when (id) {
            "menu" -> toggleToolbarExpanded()
            "symbol" -> openSymbolPicker()
            "clip" -> { toolTab = TAB_CLIP; currentFolder = null; openToolPanel() }
            "phrase" -> { toolTab = TAB_PHRASE; currentFolder = null; openToolPanel() }
            "paste" -> pasteRecentClip()
            "cursor" -> openCursorPanel()
            "hide" -> onHide()
            "handwriting" -> openHandwriting()
            "sync" -> openSync()
            "selectall" -> onSelectAll()
            "copy" -> onClipAction(android.R.id.copy)
            "clear" -> onClear()
        }
    }

    /** 从设置读取按钮顺序;补齐新增按钮、剔除已失效的 id。 */
    private fun loadToolOrder() {
        val saved = prefs().getString(KEY_TOOL_ORDER, null)
            ?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        val validIds = toolDefs.map { it.id }
        val order = saved.filter { it in validIds }.toMutableList()
        for (id in validIds) if (id !in order) order.add(id)   // 补齐(含将来新增)
        toolOrder = order
    }

    private fun saveToolOrder() {
        prefs().edit().putString(KEY_TOOL_ORDER, toolOrder.joinToString(",")).apply()
    }

    /** 粘贴最近一条剪贴板历史。 */
    private fun pasteRecentClip() {
        if (buf.isNotEmpty()) { commitText(buf.replace("'", "")); clearBuf() }
        val clip = dataStore?.recentClips(1)?.firstOrNull()
        if (clip == null) {
            Toast.makeText(this, "剪贴板暂无记录", Toast.LENGTH_SHORT).show()
            return
        }
        commitText(clip.content)
        dataStore?.touchClip(clip.uuid)   // 置顶到最近
    }

    /**
     * 常驻工具条:无拼音输入时显示。顶栏放展开按钮 + 前 TOOL_TOP_COUNT 个按钮,
     * 其余按钮收进展开面板;展开后可点「编辑排序」长按拖动调整全部按钮顺序。
     */
    private fun buildToolbar(): LinearLayout {
        loadToolOrder()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        toolbarTopRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL   // 2×6 网格:内部放 TOOLBAR_ROWS 行
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        toolbarExtraPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colPanelBg())
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        container.addView(toolbarTopRow)
        container.addView(toolbarExtraPanel)
        renderToolbar()
        return container
    }

    /** 根据当前顺序与展开/编辑状态重建整个工具条。 */
    private fun renderToolbar() {
        renderToolbarTop()
        // 展开面板时隐藏下方键位区,腾出空间;收起时恢复。
        keyboardKeysGroup?.visibility = if (toolbarExpanded) View.GONE else View.VISIBLE
        val extra = toolbarExtraPanel ?: return
        extra.removeAllViews()
        extra.visibility = if (toolbarExpanded) View.VISIBLE else View.GONE
        if (!toolbarExpanded) { toolbarEditing = false; return }
        if (toolbarEditing) renderToolbarEditList(extra) else renderToolbarExtra(extra)
    }

    /** 只刷新顶栏(2×6 网格:首格展开按钮 + 前若干按钮),用于拖动排序时的实时预览。 */
    private fun renderToolbarTop() {
        val top = toolbarTopRow ?: return
        top.removeAllViews()
        // 先按 toolOrder 顺序建好按钮(menu 也参与),不足补空格。
        val items = mutableListOf<View>()
        for (id in toolOrder.take(TOOL_TOP_COUNT)) items.add(
            if (id == "menu") toolbarButton(if (toolbarExpanded) "▲" else "☰") { toggleToolbarExpanded() }
            else toolbarButton(toolIcon(id)) { if (toolbarExpanded) collapseToolbar(); runToolAction(id) }
        )
        while (items.size < TOOLBAR_COLS * TOOLBAR_ROWS) items.add(toolbarSpacerCell())
        // 行优先映射(从右往左):列表前 TOOLBAR_COLS 项 → 上行(右→左),其余 → 下行(右→左)
        val grid = Array(TOOLBAR_ROWS) { arrayOfNulls<View>(TOOLBAR_COLS) }
        items.forEachIndexed { i, v ->
            val row = i / TOOLBAR_COLS                       // 先填满上行,再填下行
            val col = TOOLBAR_COLS - 1 - (i % TOOLBAR_COLS)  // 每行从右往左
            grid[row][col] = v
        }
        for (r in 0 until TOOLBAR_ROWS) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            for (c in 0 until TOOLBAR_COLS) row.addView(grid[r][c]!!)
            top.addView(row)
        }
    }

    /** 顶栏网格里用来占位、保持列对齐的空格子。 */
    private fun toolbarSpacerCell(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(0, dp(TOOLBAR_BTN_HEIGHT), 1f).apply {
            setMargins(dp(3), dp(TOOLBAR_BTN_MARGIN), dp(3), dp(TOOLBAR_BTN_MARGIN))
        }
    }

    /** 展开面板(非编辑):编辑入口 + 其余按钮。 */
    private fun renderToolbarExtra(extra: LinearLayout) {
        val opRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        opRow.addView(textButton("✎ 编辑排序") { toolbarEditing = true; renderToolbar() })
        extra.addView(opRow)

        val panelIds = toolOrder.drop(TOOL_TOP_COUNT)
        if (panelIds.isEmpty()) {
            extra.addView(emptyHint("按钮都在顶栏。点「编辑排序」可把按钮挪到这里。"))
            return
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        for (id in panelIds) row.addView(toolbarButton(toolIcon(id)) {
            collapseToolbar(); runToolAction(id)
        })
        repeat((TOOL_TOP_COUNT - panelIds.size).coerceAtLeast(0)) {
            row.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, dp(50), 1f) })
        }
        extra.addView(row)
    }

    /** 展开面板(编辑模式):全部按钮竖排,长按拖动排序;前 TOOL_TOP_COUNT 个进顶栏。 */
    private fun renderToolbarEditList(extra: LinearLayout) {
        extra.addView(emptyHint("长按拖动调整顺序。前 $TOOL_TOP_COUNT 个显示在顶栏,其余在此展开面板。").apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
        })
        val opRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        opRow.addView(textButton("✓ 完成") { toolbarEditing = false; renderToolbar() })
        extra.addView(opRow)

        val listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // 排序列表撑满全屏高度:屏幕高减去上方已占区域(预览 32 + 顶栏 TOOLBAR_ROWS 行网格 +
        // 编辑头部的提示与「完成」约 110),让全部按钮一屏可见、便于拖动,而非挤在下半屏的固定 4 行里。
        val reservedTop = dp(PREVIEW_HEIGHT + TOOLBAR_ROWS * TOOLBAR_ROW_HEIGHT + 110)
        val listH = (resources.displayMetrics.heightPixels - reservedTop)
            .coerceAtLeast(dp(rowHeightDp * 4))
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(listContainer)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, listH
            )
        }
        extra.addView(scroll)
        // 上面的 listH 用整屏高度估算,未扣除底部导航栏/手势条,部分机型最后一行被遮挡、
        // 滚也滚不出(列表底部落在可见区外)。布局完成后用窗口实际可见区域(已排除系统栏)校正:
        // 把列表底部对齐到可见区底并留 8dp 余量,各机型都能看全。
        scroll.post {
            val frame = android.graphics.Rect()
            scroll.getWindowVisibleDisplayFrame(frame)
            val loc = IntArray(2)
            scroll.getLocationOnScreen(loc)
            val avail = frame.bottom - loc[1] - dp(8)
            if (avail > dp(80) && avail != scroll.height) {
                scroll.layoutParams = scroll.layoutParams.apply { height = avail }
                scroll.requestLayout()
            }
        }
        // 网格按行优先从右往左填充,故列表正序即为「上行右→左、再下行右→左」
        for (id in toolOrder) listContainer.addView(toolEditRow(id, listContainer))
    }

    /** 排序编辑中的一行:图标 + 名称 + 拖动手柄;row.tag = id。 */
    @SuppressLint("ClickableViewAccessibility")
    private fun toolEditRow(id: String, container: LinearLayout): View {
        val def = toolDefs.first { it.id == id }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(colSurface())
                cornerRadius = dp(6).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(6), dp(3), dp(6), dp(3)) }
            tag = id
        }
        row.addView(TextView(this).apply {
            text = "${def.icon}  ${def.label}"
            textSize = 14f
            setTextColor(colText())
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val handle = TextView(this).apply {
            text = "≡"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#9AA0A6"))
            setPadding(dp(12), dp(10), dp(14), dp(10))
        }
        handle.setOnTouchListener(makeDragTouch(row, container) { persistToolOrder(it) })
        row.addView(handle)
        return row
    }

    /** 按编辑列表当前顺序(row.tag = id)写回按钮顺序,并实时刷新顶栏。 */
    private fun persistToolOrder(container: LinearLayout) {
        val ids = (0 until container.childCount).mapNotNull { container.getChildAt(it).tag as? String }
        if (ids.isEmpty()) return
        toolOrder = ids.toMutableList()
        saveToolOrder()
        renderToolbarTop()
    }

    private fun toolIcon(id: String) = toolDefs.first { it.id == id }.icon

    private fun toggleToolbarExpanded() {
        toolbarExpanded = !toolbarExpanded
        if (!toolbarExpanded) toolbarEditing = false
        renderToolbar()
    }

    /** 收起展开面板(点按钮执行动作前调用,回到键盘时保持简洁)。 */
    private fun collapseToolbar() {
        toolbarExpanded = false
        toolbarEditing = false
        renderToolbar()
    }

    private fun toolbarButton(text: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(colText())
            background = GradientDrawable().apply {
                setColor(colSurface())
                cornerRadius = dp(6).toFloat()
            }
            isClickable = true
            setPadding(dp(2), dp(2), dp(2), dp(2))   // 内间距:更紧凑
            layoutParams = LinearLayout.LayoutParams(0, dp(TOOLBAR_BTN_HEIGHT), 1f).apply {
                setMargins(dp(3), dp(TOOLBAR_BTN_MARGIN), dp(3), dp(TOOLBAR_BTN_MARGIN))   // 外间距:按钮间隙更小
            }
            setOnClickListener { onClick() }
        }
    }

    private fun letterRow(letters: String, sideSpacer: Float = 0f): LinearLayout {
        val row = newRow()
        if (sideSpacer > 0) row.addView(spacer(sideSpacer))
        for (c in letters) row.addView(addLetterKey(c))
        if (sideSpacer > 0) row.addView(spacer(sideSpacer))
        return row
    }

    private fun row3(): LinearLayout {
        val row = newRow()
        val sk = makeKey(shiftSymbol(), 1.5f) { onShift() }
        shiftKey = sk
        row.addView(sk)
        for (c in ROW3) row.addView(addLetterKey(c))
        row.addView(makeBackspaceKey(1.5f))
        return row
    }

    /**
     * 创建一个字母键并登记引用,文字按当前大小写状态显示。
     * 若该字母配置了上滑符号([swipeSymbols]),键的右上角显示小提示,
     * 并支持「向上滑动输入符号、普通点击输入字母」。
     */
    private fun addLetterKey(c: Char): View {
        val sym = swipeSymbols[c]
        val label = TextView(this).apply {
            text = displayChar(c).toString()
            gravity = Gravity.CENTER
            textSize = 18f
            setTextColor(colText())
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        letterKeys.add(c to label)

        val container = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(colSurface())
                cornerRadius = dp(6).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(rowHeightDp), 1f).apply {
                setMargins(dp(2), dp(3), dp(2), dp(3))
            }
            addView(label)
        }

        if (sym == null) {
            container.isClickable = true
            container.setOnClickListener { appendLetter(c) }
        } else {
            // 右上角小符号提示
            container.addView(TextView(this).apply {
                text = sym
                textSize = 10f
                setTextColor(Color.parseColor("#9AA0A6"))
                gravity = Gravity.TOP or Gravity.END
                setPadding(0, dp(2), dp(4), 0)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            })
            container.setOnTouchListener(makeSwipeTouch(c, sym))
        }
        return container
    }

    /** 处理「点击输入字母 / 上滑输入符号」的触摸监听。 */
    @SuppressLint("ClickableViewAccessibility")
    private fun makeSwipeTouch(c: Char, sym: String) = object : View.OnTouchListener {
        var startY = 0f
        var startX = 0f
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { startY = e.rawY; startX = e.rawX; return true }
                MotionEvent.ACTION_UP -> {
                    val up = startY - e.rawY              // 向上滑动距离
                    val dx = Math.abs(e.rawX - startX)
                    if (up > dp(20) && up > dx) onSwipeSymbol(sym) else appendLetter(c)
                    return true
                }
            }
            return false
        }
    }

    /** 上滑输入符号:先把未完成拼音上屏,再输出符号(与标点处理一致)。 */
    private fun onSwipeSymbol(sym: String) {
        if (buf.isNotEmpty()) { commitText(buf.replace("'", "")); clearBuf() }
        commitText(sym)
    }

    /**
     * 带上滑符号的功能键:点击执行 [onTap],向上滑动输入 [hint] 符号,右上角显示小提示。
     * 与字母键的上滑行为一致,但点击动作可自定义(如 . 键点击出句号、上滑出 ?)。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun makeSwipeKey(text: String, hint: String, weight: Float, onTap: () -> Unit): View {
        val label = TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            textSize = 18f
            setTextColor(colText())
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val container = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(colSurface())
                cornerRadius = dp(6).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(rowHeightDp), weight).apply {
                setMargins(dp(2), dp(3), dp(2), dp(3))
            }
            addView(label)
            addView(TextView(this@PinyinImeService).apply {
                this.text = hint
                textSize = 10f
                setTextColor(Color.parseColor("#9AA0A6"))
                gravity = Gravity.TOP or Gravity.END
                setPadding(0, dp(2), dp(4), 0)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            })
        }
        var startY = 0f
        var startX = 0f
        container.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { startY = e.rawY; startX = e.rawX; true }
                MotionEvent.ACTION_UP -> {
                    val up = startY - e.rawY              // 向上滑动距离
                    val dx = Math.abs(e.rawX - startX)
                    if (up > dp(20) && up > dx) onSwipeSymbol(hint) else onTap()
                    true
                }
                else -> false
            }
        }
        return container
    }

    /** 字母键应显示的字符:键面恒大写(中文模式拼音仍按小写输入,
     *  英文模式实际大小写由 Shift 状态另行决定,见 appendLetter)。 */
    private fun displayChar(c: Char): Char = c.uppercaseChar()

    private fun shiftSymbol(): String = when (shiftState) {
        SHIFT_ONCE -> "⇪"   // 单次大写:箭头下方带横线
        SHIFT_LOCK -> "⬆"   // 大写锁定:实心箭头
        else -> "⇧"          // 小写:空心箭头
    }

    private fun onShift() {
        if (cnMode) {
            // 中文模式下按大小写键:先把未完成拼音上屏,自动切到英文模式并进入单次大写
            if (buf.isNotEmpty()) { commitText(buf.replace("'", "")); clearBuf() }
            cnMode = false
            modeKey?.text = "EN"
            shiftState = SHIFT_ONCE
            updateLetterCaps()
            return
        }
        shiftState = (shiftState + 1) % 3   // 关 → 单次 → 锁定 → 关
        updateLetterCaps()
    }

    /** Shift 或模式变化后刷新所有字母键和 Shift 键的显示。 */
    private fun updateLetterCaps() {
        for ((c, tv) in letterKeys) tv.text = displayChar(c).toString()
        shiftKey?.text = shiftSymbol()
    }

    private fun functionRow(): LinearLayout {
        val row = newRow()
        // 剪贴板、光标、收起入口都在常驻工具条
        modeKey = makeKey(if (cnMode) "中" else "EN", 1.4f) { toggleMode() }
        row.addView(makeKey("1", 1.4f) { openSymbolView() })  // 数字键盘(九宫格)入口
        row.addView(modeKey)
        row.addView(makeKey(",", 1f) { onPunct("，", ",") })
        row.addView(makeKey("空格", 3f) { onSpace() })
        row.addView(makeSwipeKey(".", "?", 1f) { onPunct("。", ".") })   // 点击出句号,上滑出 ?
        row.addView(makeKey("⏎", 1.6f) { onEnter() })
        return row
    }

    private fun newRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun spacer(weight: Float) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(0, dp(rowHeightDp), weight)
    }

    private fun makeKey(
        text: String, weight: Float, heightDp: Int = rowHeightDp, textSizeSp: Float = 18f, onClick: () -> Unit
    ): TextView {
        return TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            textSize = textSizeSp
            setTextColor(colText())
            background = GradientDrawable().apply {
                setColor(colSurface())
                cornerRadius = dp(6).toFloat()
            }
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(0, dp(heightDp), weight).apply {
                setMargins(dp(2), dp(3), dp(2), dp(3))
            }
            setOnClickListener { onClick() }
        }
    }

    /**
     * 光标面板的行高:面板有 6 行,比主键盘(顶栏 2 行 + 键位 4 行,共 6 行单位,
     * 但顶栏行高固定为 TOOLBAR_ROW_HEIGHT)矮一些,按主面板总高度平均到 6 行反推,
     * 保证光标面板整体高度不超过主面板,而不是固定写死一个值。
     */
    private fun cursorRowHeightDp(): Int {
        val mainTotalDp = HEADER_HEIGHT + 4 * (rowHeightDp + 6)   // 顶栏总高 + 4 行键位(含上下margin各3dp)
        val perRow = mainTotalDp / 6 - 6
        return perRow.coerceIn(MIN_ROW_HEIGHT, rowHeightDp)
    }

    /** 删除键:点一下删一个;按住则连续删除(先 400ms 延迟,之后越按越快)。 */
    private fun makeBackspaceKey(weight: Float): TextView {
        val key = makeKey("⌫", weight) { onBackspace() }
        attachAutoRepeat(key) { onBackspace() }
        return key
    }

    /**
     * 给按键附加「按住连续触发」:按下立即触发一次,持续按住后反复触发(间隔逐步缩短),
     * 松手或手指移出按键即停止。返回 true 消费触摸事件,故不会再触发 onClick。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachAutoRepeat(view: View, action: () -> Unit) {
        var interval = 80L
        val repeat = object : Runnable {
            override fun run() {
                action()
                interval = (interval - 8L).coerceAtLeast(28L)   // 逐步加速
                mainHandler.postDelayed(this, interval)
            }
        }
        view.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    action()                                    // 立即删一次
                    interval = 80L
                    mainHandler.postDelayed(repeat, 400L)       // 按住超过 400ms 才开始连删
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // 手指滑出按键范围则停止连删(避免误删)
                    val inside = e.x >= 0 && e.y >= 0 && e.x <= v.width && e.y <= v.height
                    if (!inside) mainHandler.removeCallbacks(repeat)
                    false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(repeat)
                    true
                }
                else -> false
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- 状态机
    private fun appendLetter(c: Char) {
        if (!cnMode) {   // 英文模式:按 Shift 状态决定大小写,单次大写上屏后自动复位
            val ch = if (shiftState != SHIFT_OFF) c.uppercaseChar() else c
            commitText(ch.toString())
            if (shiftState == SHIFT_ONCE) { shiftState = SHIFT_OFF; updateLetterCaps() }
            return
        }
        if (buf.length < PinyinDict.MAX_PINYIN) { buf += c; refresh() }
    }

    private fun refresh() {
        if (buf.isEmpty()) { clearBuf(); return }
        val d = dict
        if (d != null) {
            val (c, s) = d.candidates(buf)
            cands = c; segs = s
        } else {
            cands = emptyList(); segs = buf.map { it.toString() }
        }
        updatePreview()
    }

    private fun clearBuf() {
        buf = ""; cands = emptyList(); segs = emptyList()
        updatePreview()
    }

    private fun choose(index: Int) {
        if (index >= cands.size) return
        val cand = cands[index]
        commitText(cand.word)
        val consumed = segs.subList(0, cand.nseg).toList()
        val d = dict
        if (d != null) {
            try {
                val upd = d.bump(cand.word, consumed)
                if (upd != null) writeBack(cand.word, upd.first, upd.second)
            } catch (e: Exception) { /* 调权失败不影响上屏 */ }
        }
        // 去掉已消耗音节对应的字母,余下继续组词
        var b = buf
        for (s in consumed) {
            b = b.trimStart('\'')
            if (b.startsWith(s)) b = b.substring(s.length)
        }
        buf = b.trimStart('\'')
        if (buf.isNotEmpty()) refresh() else clearBuf()
    }

    private fun onBackspace() {
        if (buf.isNotEmpty()) { buf = buf.dropLast(1); refresh(); return }
        val ic = currentInputConnection ?: return
        // 有选区时(如「全选」后),退格应删除整个选区;deleteSurroundingText 不会删选中文本。
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
            selecting = false
            updateSelectKey()
        } else {
            ic.deleteSurroundingText(1, 0)
        }
    }

    private fun onSpace() {
        if (cnMode && buf.isNotEmpty() && cands.isNotEmpty()) choose(0)
        else commitText(" ")
    }

    private fun onEnter() {
        if (buf.isNotEmpty()) { commitText(buf.replace("'", "")); clearBuf(); return }
        // 无拼音时:优先触发输入框声明的编辑器动作(地址栏「前往」、搜索框「搜索」等);
        // 没有具体动作或被禁用时才退回发普通 Enter(换行/确认)
        val ic = currentInputConnection
        val ei = currentInputEditorInfo
        if (ic != null && ei != null) {
            val opts = ei.imeOptions
            val action = opts and android.view.inputmethod.EditorInfo.IME_MASK_ACTION
            val noEnterAction =
                (opts and android.view.inputmethod.EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
            if (!noEnterAction &&
                action != android.view.inputmethod.EditorInfo.IME_ACTION_NONE &&
                action != android.view.inputmethod.EditorInfo.IME_ACTION_UNSPECIFIED) {
                ic.performEditorAction(action)
                return
            }
        }
        sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
    }

    private fun onPunct(cn: String, en: String) {
        // 组词中按标点:先把当前拼音字母上屏,再发标点(简化:不在标点路径做部分选词)
        if (buf.isNotEmpty()) { commitText(buf.replace("'", "")); clearBuf() }
        // 逗号、句号无论中英文模式都输出英文版本
        commitText(en)
    }

    /** 收回输入法面板:丢弃未完成的拼音,隐藏键盘。 */
    private fun onHide() {
        if (buf.isNotEmpty()) clearBuf()
        requestHideSelf(0)
    }

    // ---------------------------------------------------------------- 工具面板
    /** 工具面板顶部 Tab 当前选中:剪贴板 / 常用语。 */
    private var toolTab = TAB_CLIP
    private var clipTabKey: TextView? = null    // 顶部「剪贴板」Tab,选中时背景高亮
    private var phraseTabKey: TextView? = null  // 顶部「常用语」Tab,选中时背景高亮

    /** 构建工具面板:顶部 Tab + 内容区(内容区随 Tab/操作动态重建)。 */
    private fun buildToolPanel(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colPanelBg())
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // 顶部 Tab 行:剪贴板 / 常用语 / 返回键盘(比普通键位行矮,给内容区多留空间)
        val tabRow = newRow()
        clipTabKey = makeKey("剪贴板", 2f, heightDp = TOOL_TAB_ROW_HEIGHT, textSizeSp = 15f) { toolTab = TAB_CLIP; currentFolder = null; renderPanel() }
        phraseTabKey = makeKey("常用语", 2f, heightDp = TOOL_TAB_ROW_HEIGHT, textSizeSp = 15f) { toolTab = TAB_PHRASE; currentFolder = null; renderPanel() }
        tabRow.addView(clipTabKey)
        tabRow.addView(phraseTabKey)
        tabRow.addView(makeKey("☁", 1.4f, heightDp = TOOL_TAB_ROW_HEIGHT, textSizeSp = 15f) {
            // 从工具面板进同步页:记下当前 Tab/文件夹,返回外部输入框时自动重开本面板
            pendingReopenPanel = true
            pendingReopenTab = toolTab
            pendingReopenFolderUuid = currentFolder?.uuid
            openSync()
        })
        tabRow.addView(makeKey("⌨", 1.4f, heightDp = TOOL_TAB_ROW_HEIGHT, textSizeSp = 15f) { closeToolPanel() })
        panel.addView(tabRow)

        // 固定栏:常用语的文件夹行放这里,向下滚动内容时始终悬停显示,不随内容滚走
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        panelHeader = header
        panel.addView(header)

        // 内容区:固定高度的可滚动列表(高度与键盘大致相当,避免面板忽高忽低)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        panelContent = content
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(rowHeightDp * 4 + 24)
            )
        }
        panel.addView(scroll)
        return panel
    }

    private fun openToolPanel() {
        if (buf.isNotEmpty()) { commitText(buf.replace("'", "")); clearBuf() }
        renderPanel()
        keyboardView?.visibility = View.GONE
        panelView?.visibility = View.VISIBLE
    }

    private fun closeToolPanel() {
        panelView?.visibility = View.GONE
        cursorView?.visibility = View.GONE
        symbolView?.visibility = View.GONE
        symbolPickerView?.visibility = View.GONE
        keyboardView?.visibility = View.VISIBLE
    }

    /** 根据当前 Tab 重新填充内容区,并刷新 Tab 高亮。 */
    private fun renderPanel() {
        updateTabHighlight()
        val content = panelContent ?: return
        content.removeAllViews()
        panelHeader?.removeAllViews()
        when (toolTab) {
            TAB_CLIP -> renderClipboard(content)
            else -> renderPhrase(content)
        }
    }

    /** 选中的 Tab 背景设为蓝色,未选中为白色。 */
    private fun updateTabHighlight() {
        setTabBg(clipTabKey, toolTab == TAB_CLIP)
        setTabBg(phraseTabKey, toolTab == TAB_PHRASE)
    }

    private fun setTabBg(tv: TextView?, selected: Boolean) {
        tv?.background = GradientDrawable().apply {
            setColor(if (selected) Color.parseColor("#4A90D9") else colSurface())
            cornerRadius = dp(6).toFloat()
        }
    }

    // ---- 剪贴板面板 ----
    private fun renderClipboard(content: LinearLayout) {
        val clips = dataStore?.recentClips() ?: emptyList()
        if (clips.isEmpty()) {
            content.addView(emptyHint("剪贴板暂无记录。复制文字后再唤起键盘即可自动收录。"))
            return
        }
        for (clip in clips) {
            content.addView(listItem(clip.content, onClick = {
                commitText(clip.content)
                dataStore?.touchClip(clip.uuid)   // 置顶到最近
                closeToolPanel()
            }, onDelete = {
                dataStore?.deleteClip(clip.uuid)
                renderPanel()
            }))
        }
    }

    // ---- 常用语面板 ----
    private fun renderPhrase(content: LinearLayout) {
        val ds = dataStore ?: return
        // 子 Tab 行:最近 / 各文件夹 / 新建文件夹
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val barScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(bar)
        }
        bar.addView(chip("最近", selected = currentFolder == null) {
            currentFolder = null; renderPanel()
        })
        // 文件夹 chip 放进独立子容器,长按可在其中左右拖动排序
        val foldersContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        for (f in ds.folders()) foldersContainer.addView(folderChip(f, foldersContainer))
        bar.addView(foldersContainer)
        bar.addView(chip("+ 文件夹", selected = false) { promptNewFolder() })
        // 放进固定栏(panelHeader),而不是可滚动的 content:下滑内容列表时文件夹行始终悬停可见
        panelHeader?.addView(barScroll)

        val folder = currentFolder
        if (folder == null) {
            // 「最近」:最近点选过的常用语
            val recents = ds.recentPhrases()
            if (recents.isEmpty()) {
                content.addView(emptyHint("还没有最近使用的常用语。\n进入文件夹新建条目并点选后,会出现在这里。"))
                return
            }
            // 「最近」按使用时间排序,不支持拖动;仍可长按删除
            for (p in recents) content.addView(phraseItem(p, container = null, draggable = false))
        } else {
            // 某个文件夹:条目列表 + 新建条目 + 删除文件夹
            val actionRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(8), dp(2), dp(8), dp(2))
            }
            actionRow.addView(textButton("+ 新建条目") { promptNewPhrase(folder) })
            actionRow.addView(textButton("重命名") { promptRenameFolder(folder) })
            actionRow.addView(textButton("删除该文件夹") { confirmDeleteFolder(folder) })
            content.addView(actionRow)

            val items = ds.phrasesIn(folder.uuid)
            if (items.isEmpty()) {
                content.addView(emptyHint("「${folder.name}」还没有条目,点上方「+ 新建条目」添加。"))
                return
            }
            // 条目放进独立子容器,拖动排序时只在此容器内重排,不受上方 Tab/操作行干扰
            val itemsContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            content.addView(itemsContainer)
            for (p in items) itemsContainer.addView(phraseItem(p, itemsContainer, draggable = true))
        }
    }

    // ---- 新建(跳转编辑页)/ 删除 ----
    /** 新建文件夹:跳到编辑页输入名称(输入法窗口内无法打字)。 */
    private fun promptNewFolder() =
        launchTextInput(PhraseEditActivity.MODE_FOLDER, "新建文件夹", null, multiline = false)

    /** 在指定文件夹下新建常用语:跳到编辑页输入内容。 */
    private fun promptNewPhrase(folder: DataStore.Folder) =
        launchTextInput(
            PhraseEditActivity.MODE_PHRASE, "在「${folder.name}」中新建常用语",
            folder.uuid, multiline = true
        )

    /** 重命名文件夹:跳到编辑页,预填当前名称。 */
    private fun promptRenameFolder(folder: DataStore.Folder) =
        launchTextInput(
            PhraseEditActivity.MODE_RENAME_FOLDER, "重命名文件夹",
            folder.uuid, multiline = false, initial = folder.name
        )

    /** 编辑常用语:跳到编辑页,预填当前描述+内容;返回后定位回该条目所在文件夹。 */
    private fun promptEditPhrase(p: DataStore.Phrase) =
        launchTextInput(
            PhraseEditActivity.MODE_EDIT_PHRASE, "编辑常用语",
            folderUuid = p.folderUuid, multiline = true,
            initial = p.content, initialDesc = p.description, phraseUuid = p.uuid
        )

    /**
     * 跳转到 [PhraseEditActivity] 编辑文字。记下返回后要重开的文件夹,
     * 保存返回时由 onStartInputView 重新打开常用语面板。
     */
    private fun launchTextInput(
        mode: String, title: String, folderUuid: String?,
        multiline: Boolean, initial: String? = null, initialDesc: String? = null,
        phraseUuid: String? = null
    ) {
        pendingReopenPanel = true
        pendingReopenFolderUuid = folderUuid
        pendingReopenTab = TAB_PHRASE
        val intent = android.content.Intent(this, PhraseEditActivity::class.java).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(PhraseEditActivity.EXTRA_MODE, mode)
            putExtra(PhraseEditActivity.EXTRA_TITLE, title)
            putExtra(PhraseEditActivity.EXTRA_FOLDER_UUID, folderUuid)
            putExtra(PhraseEditActivity.EXTRA_PHRASE_UUID, phraseUuid)
            putExtra(PhraseEditActivity.EXTRA_MULTILINE, multiline)
            putExtra(PhraseEditActivity.EXTRA_INITIAL, initial)
            putExtra(PhraseEditActivity.EXTRA_INITIAL_DESC, initialDesc)
        }
        startActivity(intent)
    }

    private fun confirmDeleteFolder(folder: DataStore.Folder) {
        val dlg = AlertDialog.Builder(this)
            .setTitle("删除文件夹")
            .setMessage("确定删除「${folder.name}」及其中所有条目?")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                dataStore?.deleteFolder(folder.uuid); currentFolder = null; renderPanel()
            }
            .create()
        showOverIme(dlg)
    }

    /**
     * 让对话框能显示在输入法窗口之上。
     * 关键:必须用「当前输入视图所在窗口」的 windowToken 作为附着 token,
     * 而不是 window.attributes.token(后者在输入法窗口里无效,show() 会抛 BadTokenException)。
     * token、type 必须在 show() 之前写回 attributes。
     */
    private fun showOverIme(dlg: AlertDialog) {
        val token = (keyboardView ?: panelView ?: cursorView ?: symbolView)?.windowToken
            ?: window?.window?.decorView?.windowToken
        val w = dlg.window
        if (token != null && w != null) {
            val lp = w.attributes
            lp.token = token
            lp.type = android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
            w.attributes = lp
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        }
        dlg.show()
    }

    // ---- 面板内的小部件 ----
    /** 一行列表项:左侧文本(可点击上屏),右侧 ✕ 删除按钮默认隐藏,长按文本后显示/收起。 */
    private fun listItem(text: String, onClick: () -> Unit, onDelete: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(colSurface())
                cornerRadius = dp(6).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(6), dp(3), dp(6), dp(3)) }
        }
        // ✕ 删除按钮:默认隐藏,长按文本后出现
        val delBtn = TextView(this).apply {
            this.text = "✕"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#9AA0A6"))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true
            visibility = View.GONE
            setOnClickListener { onDelete() }
        }
        row.addView(TextView(this).apply {
            this.text = text
            textSize = 12f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(colText())
            setPadding(dp(12), dp(12), dp(12), dp(12))
            isClickable = true
            isLongClickable = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
            setOnLongClickListener {
                // 长按切换:显示/隐藏删除按钮
                delBtn.visibility =
                    if (delBtn.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                true
            }
        })
        row.addView(delBtn)
        return row
    }

    /**
     * 常用语条目行:文本点击上屏、长按弹出/收起删除按钮;
     * draggable 时右侧带「≡」手柄,按住可在 [container] 内拖动排序。
     * row.tag 记 uuid,拖动结束据此持久化顺序。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun phraseItem(p: DataStore.Phrase, container: LinearLayout?, draggable: Boolean): View {
        val ds = dataStore
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(colSurface())
                cornerRadius = dp(6).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(6), dp(3), dp(6), dp(3)) }
            tag = p.uuid
        }

        // 操作按钮:默认隐藏,长按文本后出现(编辑 / 删除)
        val editBtn = TextView(this).apply {
            text = "编辑"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#4A90D9"))
                cornerRadius = dp(6).toFloat()
            }
            setPadding(dp(14), dp(8), dp(14), dp(8))
            isClickable = true
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(4), dp(6), dp(4)) }
            setOnClickListener { promptEditPhrase(p) }
        }

        val delBtn = TextView(this).apply {
            text = "删除"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E53935"))
                cornerRadius = dp(6).toFloat()
            }
            setPadding(dp(14), dp(8), dp(14), dp(8))
            isClickable = true
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(4), dp(6), dp(4)) }
            setOnClickListener { ds?.deletePhrase(p.uuid); renderPanel() }
        }

        // 描述 + 实际内容:描述在前(仅展示,可为空),点击/长按整体响应;
        // 点击发送时只发实际内容(p.content),描述不会上屏。
        val textContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            isClickable = true
            isLongClickable = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { commitText(p.content); ds?.touchPhrase(p.uuid); closeToolPanel() }
            setOnLongClickListener {
                // 长按切换:同时显示/隐藏 编辑、删除 按钮
                val show = if (delBtn.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                editBtn.visibility = show
                delBtn.visibility = show
                true
            }
        }
        if (p.description.isNotBlank()) {
            textContainer.addView(TextView(this).apply {
                text = p.description
                textSize = 10f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(Color.parseColor("#9AA0A6"))
            })
        }
        textContainer.addView(TextView(this).apply {
            text = p.content
            textSize = 12f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(colText())
        })

        row.addView(textContainer)
        row.addView(editBtn)
        row.addView(delBtn)

        if (draggable && container != null) {
            val handle = TextView(this).apply {
                text = "≡"
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#9AA0A6"))
                setPadding(dp(12), dp(6), dp(14), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            handle.setOnTouchListener(makeDragTouch(row, container))
            row.addView(handle)
        }
        return row
    }

    /**
     * 拖动手柄的触摸逻辑(带松手前预览):
     * 被拖行用 translationY 跟着手指浮动(translationZ 提到其他行之上,不改 child 顺序);
     * 其他行根据当前目标位置用带动画的 translationY 上下让位;松手时才真正重排并清除位移、持久化。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun makeDragTouch(
        row: View,
        container: LinearLayout,
        onDrop: (LinearLayout) -> Unit = { persistPhraseOrder(it) }
    ) = object : View.OnTouchListener {
        var startRawY = 0f
        var origIndex = -1
        var target = -1
        var rowH = 0

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // 让外层 ScrollView 不拦截后续移动事件(会向上传播到祖先)
                    (v.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(true)
                    startRawY = e.rawY
                    origIndex = container.indexOfChild(row)
                    target = origIndex
                    rowH = if (row.height > 0) row.height else dp(48)
                    row.translationZ = dp(8).toFloat()   // 浮到其他行之上(不改变 child 顺序)
                    row.alpha = 0.9f
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (origIndex < 0) return true
                    row.translationY = e.rawY - startRawY     // 跟手浮动
                    // 用各行原始布局中点判断目标插入位置(getTop 不含 translationY,稳定)
                    val loc = IntArray(2)
                    container.getLocationOnScreen(loc)
                    val touchY = e.rawY - loc[1]
                    var t = container.childCount - 1
                    for (i in 0 until container.childCount) {
                        val c = container.getChildAt(i)
                        if (touchY < c.top + c.height / 2f) { t = i; break }
                    }
                    if (t != target) {                        // 目标变化时,其他行带动画让位
                        target = t
                        for (i in 0 until container.childCount) {
                            if (i == origIndex) continue
                            val ty = when {
                                origIndex < target && i in (origIndex + 1)..target -> -rowH.toFloat()
                                origIndex > target && i in target until origIndex -> rowH.toFloat()
                                else -> 0f
                            }
                            container.getChildAt(i).animate().translationY(ty).setDuration(120).start()
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (origIndex < 0) return true
                    val finalTarget = target
                    origIndex = -1
                    row.alpha = 1f
                    row.translationZ = 0f
                    // 重排会移除正在接收触摸的行,必须延到事件派发结束后执行,否则
                    // removeView 触发 ACTION_CANCEL 再次进入本回调,造成无限递归崩溃。
                    container.post {
                        row.translationY = 0f
                        for (i in 0 until container.childCount) {
                            val c = container.getChildAt(i)
                            c.animate().cancel()
                            c.translationY = 0f
                        }
                        val cur = container.indexOfChild(row)
                        if (cur >= 0 && finalTarget != cur && finalTarget in 0 until container.childCount) {
                            container.removeViewAt(cur)
                            container.addView(row, finalTarget.coerceIn(0, container.childCount))
                        }
                        onDrop(container)
                    }
                    return true
                }
            }
            return false
        }
    }

    /** 按容器中当前条目顺序(row.tag = uuid)写回排序。 */
    private fun persistPhraseOrder(container: LinearLayout) {
        val uuids = (0 until container.childCount).mapNotNull { container.getChildAt(it).tag as? String }
        if (uuids.isNotEmpty()) dataStore?.reorderPhrases(uuids)
    }

    /** 常用语子 Tab 的小标签。 */
    private fun chip(text: String, selected: Boolean, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(if (selected) Color.WHITE else colText())
            background = GradientDrawable().apply {
                setColor(if (selected) Color.parseColor("#4A90D9") else colSurface())
                cornerRadius = dp(14).toFloat()
            }
            setPadding(dp(12), dp(5), dp(12), dp(5))
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(4), 0, dp(4), 0) }
            setOnClickListener { onClick() }
        }
    }

    /**
     * 可拖动排序的文件夹标签:点击进入该文件夹,长按后左右拖动可在 [container] 内排序。
     * tag 记 uuid,拖动结束后据此持久化顺序。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun folderChip(folder: DataStore.Folder, container: LinearLayout): TextView {
        val selected = currentFolder?.uuid == folder.uuid
        val tv = TextView(this).apply {
            text = folder.name
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(if (selected) Color.WHITE else colText())
            background = GradientDrawable().apply {
                setColor(if (selected) Color.parseColor("#4A90D9") else colSurface())
                cornerRadius = dp(14).toFloat()
            }
            setPadding(dp(12), dp(5), dp(12), dp(5))
            isClickable = true
            isLongClickable = true
            tag = folder.uuid
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(4), 0, dp(4), 0) }
        }
        tv.setOnTouchListener(makeFolderDragTouch(tv, container, folder))
        return tv
    }

    /**
     * 文件夹标签的触摸逻辑:长按进入拖动,横向跟手浮动 + 其他标签让位(松手前预览);
     * 松手后重排并持久化。未触发长按时:轻点=进入该文件夹,横滑=交还 ScrollView 滚动。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun makeFolderDragTouch(chip: View, container: LinearLayout, folder: DataStore.Folder) =
        object : View.OnTouchListener {
            val slop = ViewConfiguration.get(this@PinyinImeService).scaledTouchSlop
            var startRawX = 0f
            var dragging = false
            var origIndex = -1
            var target = -1
            var chipW = 0
            val longPress = Runnable {
                dragging = true
                origIndex = container.indexOfChild(chip)
                target = origIndex
                chipW = chip.width
                (chip.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(true)
                chip.alpha = 0.85f
                chip.translationZ = dp(8).toFloat()
            }

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startRawX = e.rawX
                        dragging = false
                        mainHandler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!dragging) {
                            // 长按未触发就移动:视为滚动,取消长按并交还 ScrollView
                            if (Math.abs(e.rawX - startRawX) > slop) {
                                mainHandler.removeCallbacks(longPress)
                                (chip.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(false)
                            }
                            return false
                        }
                        chip.translationX = e.rawX - startRawX     // 横向跟手
                        val loc = IntArray(2)
                        container.getLocationOnScreen(loc)
                        val touchX = e.rawX - loc[0]
                        var t = container.childCount - 1
                        for (i in 0 until container.childCount) {
                            val c = container.getChildAt(i)
                            if (touchX < c.left + c.width / 2f) { t = i; break }
                        }
                        if (t != target) {                          // 目标变化时,其他标签带动画让位
                            target = t
                            for (i in 0 until container.childCount) {
                                if (i == origIndex) continue
                                val tx = when {
                                    origIndex < target && i in (origIndex + 1)..target -> -chipW.toFloat()
                                    origIndex > target && i in target until origIndex -> chipW.toFloat()
                                    else -> 0f
                                }
                                container.getChildAt(i).animate().translationX(tx).setDuration(120).start()
                            }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        mainHandler.removeCallbacks(longPress)
                        if (!dragging) {
                            // 没进入拖动:轻点(未明显移动)= 进入该文件夹
                            if (e.actionMasked == MotionEvent.ACTION_UP &&
                                Math.abs(e.rawX - startRawX) <= slop) {
                                currentFolder = folder; renderPanel()
                            }
                            return true
                        }
                        val finalTarget = target
                        dragging = false
                        chip.alpha = 1f
                        chip.translationZ = 0f
                        // 重排延到事件派发结束后执行,避免 removeView 触发 CANCEL 递归
                        container.post {
                            chip.translationX = 0f
                            for (i in 0 until container.childCount) {
                                val c = container.getChildAt(i)
                                c.animate().cancel()
                                c.translationX = 0f
                            }
                            val cur = container.indexOfChild(chip)
                            if (cur >= 0 && finalTarget != cur && finalTarget in 0 until container.childCount) {
                                container.removeViewAt(cur)
                                container.addView(chip, finalTarget.coerceIn(0, container.childCount))
                            }
                            persistFolderOrder(container)
                        }
                        return true
                    }
                }
                return false
            }
        }

    /** 按容器中当前文件夹顺序(chip.tag = uuid)写回排序。 */
    private fun persistFolderOrder(container: LinearLayout) {
        val uuids = (0 until container.childCount).mapNotNull { container.getChildAt(it).tag as? String }
        if (uuids.isNotEmpty()) dataStore?.reorderFolders(uuids)
    }

    private fun textButton(text: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.parseColor("#4A90D9"))
            setPadding(dp(6), dp(6), dp(12), dp(6))
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    private fun emptyHint(msg: String): TextView {
        return TextView(this).apply {
            text = msg
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#808080"))
            setPadding(dp(24), dp(32), dp(24), dp(32))
        }
    }

    // ---------------------------------------------------------------- 数字键盘(九宫格)
    /**
     * 构建九宫格数字键盘:左侧 1-9 + 0 排成九宫格,右侧一列功能键(退格/减号/空格/回车)。
     * 此页不参与拼音组词,数字键直接上屏。
     */
    private fun buildSymbolView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colPanelBg())
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val r1 = newRow()
        for (n in listOf("1", "2", "3")) r1.addView(makeKey(n, 1f) { onSymbolInput(n) })
        r1.addView(makeBackspaceKey(1f))
        root.addView(r1)

        val r2 = newRow()
        for (n in listOf("4", "5", "6")) r2.addView(makeKey(n, 1f) { onSymbolInput(n) })
        r2.addView(makeKey("-", 1f) { onSymbolInput("-") })   // 右侧列:输入减号
        root.addView(r2)

        val r3 = newRow()
        for (n in listOf("7", "8", "9")) r3.addView(makeKey(n, 1f) { onSymbolInput(n) })
        r3.addView(makeKey(".", 1f) { onSymbolInput(".") })   // 小数点
        root.addView(r3)

        val r4 = newRow()
        r4.addView(makeKey("返回", 1f) { closeSymbolView() })   // 0 左侧返回键:回到拼音键盘
        r4.addView(makeKey("0", 1f) { onSymbolInput("0") })   // 0 占一列
        r4.addView(makeKey("空格", 1f) { commitText(" ") })
        r4.addView(makeKey("⏎", 1f) { onEnter() })
        root.addView(r4)
        return root
    }

    /** 数字键:直接上屏对应字符(进入本页时已清空拼音缓冲)。 */
    private fun onSymbolInput(s: String) {
        commitText(s)
    }

    private fun openSymbolView() {
        if (buf.isNotEmpty()) { commitText(buf.replace("'", "")); clearBuf() }
        keyboardView?.visibility = View.GONE
        panelView?.visibility = View.GONE
        cursorView?.visibility = View.GONE
        symbolView?.visibility = View.VISIBLE
    }

    private fun closeSymbolView() {
        symbolView?.visibility = View.GONE
        keyboardView?.visibility = View.VISIBLE
    }

    // ---------------------------------------------------------------- 符号选择页
    /** 可选符号全集(主面板里没有的)。新增符号往这里加即可,首次默认排在末尾。 */
    private val symbolCatalog = listOf("=", "·", "(", ")", "#")

    /** 每行放几个符号键。 */
    private val symbolCols = 6

    /** 从设置读取符号顺序;补齐新增符号、剔除失效项(同工具按钮顺序的处理)。 */
    private fun loadSymbolOrder() {
        val saved = prefs().getString(KEY_SYMBOL_ORDER, null)
            ?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        val order = saved.filter { it in symbolCatalog }.toMutableList()
        for (s in symbolCatalog) if (s !in order) order.add(s)   // 补齐新增
        symbolOrder = order
    }

    private fun saveSymbolOrder() {
        prefs().edit().putString(KEY_SYMBOL_ORDER, symbolOrder.joinToString(",")).apply()
    }

    private fun buildSymbolPicker(): View {
        loadSymbolOrder()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colPanelBg())
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        symbolPickerGrid = grid
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(grid)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(rowHeightDp * 3 + 18)
            )
        }
        root.addView(scroll)
        // 底部返回行
        val back = newRow()
        back.addView(makeKey("返回", 1f) { closeSymbolPicker() })
        root.addView(back)
        renderSymbolPicker()
        return root
    }

    /** 按当前(最近使用在前)顺序重建符号网格。 */
    private fun renderSymbolPicker() {
        val grid = symbolPickerGrid ?: return
        grid.removeAllViews()
        var row: LinearLayout? = null
        symbolOrder.forEachIndexed { i, sym ->
            if (i % symbolCols == 0) {
                row = newRow().also { grid.addView(it) }
            }
            row!!.addView(makeKey(sym, 1f) { onSymbolPick(sym) })
        }
        // 末行用空白占位补齐,保持每个符号键宽度一致
        row?.let { r ->
            val rem = symbolOrder.size % symbolCols
            if (rem != 0) repeat(symbolCols - rem) { r.addView(spacer(1f)) }
        }
    }

    /** 选中一个符号:上屏、置顶到最近、持久化并刷新顺序,然后回到键盘。 */
    private fun onSymbolPick(sym: String) {
        commitText(sym)
        symbolOrder.remove(sym)
        symbolOrder.add(0, sym)   // 置顶为最近使用
        saveSymbolOrder()
        renderSymbolPicker()
        closeSymbolPicker()
    }

    private fun openSymbolPicker() {
        if (buf.isNotEmpty()) { commitText(buf.replace("'", "")); clearBuf() }
        keyboardView?.visibility = View.GONE
        panelView?.visibility = View.GONE
        cursorView?.visibility = View.GONE
        symbolView?.visibility = View.GONE
        renderSymbolPicker()   // 按最近使用顺序重排后再显示
        symbolPickerView?.visibility = View.VISIBLE
    }

    private fun closeSymbolPicker() {
        symbolPickerView?.visibility = View.GONE
        keyboardView?.visibility = View.VISIBLE
    }

    // ---------------------------------------------------------------- 手写输入
    /**
     * 手写面板:候选行 + 状态提示 + 画板 + 功能行。
     * 识别用 Google ML Kit Digital Ink(中文 zh-Hani 模型),首次需联网下载,之后离线可用。
     */
    private fun buildHandwritingPanel(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colPanelBg())
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // 候选行(横向滚动)
        val cand = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        hwCandidates = cand
        panel.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(cand)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46)
            )
        })

        // 状态提示(下载/识别/出错)
        hwStatus = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#9AA0A6"))
            setPadding(dp(12), 0, dp(12), dp(2))
        }
        panel.addView(hwStatus)

        // 画板
        val pad = HandwritingPad(this).apply {
            background = GradientDrawable().apply {
                setColor(colSurface())
                cornerRadius = dp(6).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(180)
            ).apply { setMargins(dp(6), dp(2), dp(6), dp(4)) }
        }
        handwritingPad = pad
        panel.addView(pad)

        // 功能行
        val row = newRow()
        row.addView(makeKey("清除", 1f) { clearHandwriting() })
        row.addView(makeBackspaceKey(1f))
        row.addView(makeKey("空格", 2f) { commitText(" ") })
        row.addView(makeKey("⏎", 1f) { onEnter() })
        row.addView(makeKey("拼音", 1.4f) { closeHandwriting() })
        panel.addView(row)
        return panel
    }

    /** 画板:捕捉触摸笔迹,边画边收集成 ML Kit 的 Ink;停笔后由外层防抖触发识别。 */
    @SuppressLint("ViewConstructor")
    private inner class HandwritingPad(context: Context) : View(context) {
        private val paint = Paint().apply {
            color = colText()
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = dp(3).toFloat()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val committedPaths = ArrayList<Path>()
        private var livePath: Path? = null
        private var inkBuilder = Ink.builder()
        private var strokeBuilder: Ink.Stroke.Builder? = null

        fun isEmpty() = committedPaths.isEmpty() && livePath == null

        fun clearPad() {
            committedPaths.clear()
            livePath = null
            inkBuilder = Ink.builder()
            strokeBuilder = null
            invalidate()
        }

        fun currentInk(): Ink = inkBuilder.build()

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val x = event.x; val y = event.y; val t = System.currentTimeMillis()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancelPendingRecognition()
                    livePath = Path().apply { moveTo(x, y) }
                    strokeBuilder = Ink.Stroke.builder().apply { addPoint(Ink.Point.create(x, y, t)) }
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    livePath?.lineTo(x, y)
                    strokeBuilder?.addPoint(Ink.Point.create(x, y, t))
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    livePath?.let { committedPaths.add(it) }
                    livePath = null
                    strokeBuilder?.let { inkBuilder.addStroke(it.build()) }
                    strokeBuilder = null
                    invalidate()
                    scheduleRecognition()
                }
            }
            return true
        }

        override fun onDraw(canvas: Canvas) {
            for (p in committedPaths) canvas.drawPath(p, paint)
            livePath?.let { canvas.drawPath(it, paint) }
        }
    }

    private fun setHwStatus(s: String) { hwStatus?.text = s }

    private fun scheduleRecognition() {
        mainHandler.removeCallbacks(recognizeRunnable)
        mainHandler.postDelayed(recognizeRunnable, 600)   // 停笔 600ms 后识别
    }

    private fun cancelPendingRecognition() {
        mainHandler.removeCallbacks(recognizeRunnable)
    }

    /** 确保中文手写模型就绪(必要时下载),就绪后回调 onReady。 */
    private fun ensureRecognizer(onReady: () -> Unit) {
        if (modelReady && recognizer != null) { onReady(); return }
        try {
            val modelId = DigitalInkRecognitionModelIdentifier.fromLanguageTag("zh-Hani")
            if (modelId == null) { setHwStatus("此设备不支持中文手写模型"); return }
            val model = DigitalInkRecognitionModel.builder(modelId).build()
            val rec = DigitalInkRecognition.getClient(
                DigitalInkRecognizerOptions.builder(model).build()
            )
            val manager = RemoteModelManager.getInstance()
            manager.isModelDownloaded(model).addOnSuccessListener { downloaded ->
                if (downloaded) {
                    recognizer = rec; modelReady = true; setHwStatus(""); onReady()
                } else {
                    setHwStatus("首次使用:正在下载中文手写模型(需联网)…")
                    manager.download(model, DownloadConditions.Builder().build())
                        .addOnSuccessListener {
                            recognizer = rec; modelReady = true; setHwStatus(""); onReady()
                        }
                        .addOnFailureListener { e ->
                            setHwStatus("模型下载失败(请联网后重开手写):${e.message}")
                        }
                }
            }.addOnFailureListener { e ->
                setHwStatus("手写模型检查失败:${e.message}")
            }
        } catch (e: Exception) {
            setHwStatus("手写初始化失败:${e.message}")
        }
    }

    private fun runRecognition() {
        val pad = handwritingPad ?: return
        if (pad.isEmpty()) return
        ensureRecognizer {
            val rec = recognizer ?: return@ensureRecognizer
            try {
                rec.recognize(pad.currentInk())
                    .addOnSuccessListener { result -> showHwCandidates(result) }
                    .addOnFailureListener { e -> setHwStatus("识别失败:${e.message}") }
            } catch (e: Exception) {
                setHwStatus("识别异常:${e.message}")
            }
        }
    }

    private fun showHwCandidates(result: RecognitionResult) {
        val cont = hwCandidates ?: return
        cont.removeAllViews()
        for (c in result.candidates.take(12)) cont.addView(hwCandView(c.text))
    }

    /** 手写候选项:点击上屏并清空画板,方便继续写下一个字。 */
    private fun hwCandView(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 20f
        setTextColor(colText())
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(4), dp(14), dp(4))
        isClickable = true
        setOnClickListener {
            commitText(text)
            handwritingPad?.clearPad()
            hwCandidates?.removeAllViews()
        }
    }

    private fun clearHandwriting() {
        handwritingPad?.clearPad()
        hwCandidates?.removeAllViews()
        setHwStatus("")
    }

    private fun openHandwriting() {
        if (buf.isNotEmpty()) { commitText(buf.replace("'", "")); clearBuf() }
        keyboardView?.visibility = View.GONE
        panelView?.visibility = View.GONE
        cursorView?.visibility = View.GONE
        symbolView?.visibility = View.GONE
        handwritingView?.visibility = View.VISIBLE
        clearHandwriting()
        ensureRecognizer { }   // 预热;若未下载会提示并开始下载
    }

    private fun closeHandwriting() {
        cancelPendingRecognition()
        handwritingView?.visibility = View.GONE
        keyboardView?.visibility = View.VISIBLE
    }

    // ---------------------------------------------------------------- 光标操作面板
    /**
     * 构建光标操作面板:十字方向键 + 中间「选择」键(开启后移动方向键会扩展选区)。
     * 编辑动作放在十字的四个斜对角上(全选/剪切/复制/粘贴),不再拥挤在顶部;
     * 清除、返回键盘放在最底一行。
     */
    private fun buildCursorPanel(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colPanelBg())
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // 面板有 6 行,比主键盘键位区(4 行)多,用更矮的行高 + 更小的字号,
        // 使面板总高度不超过主面板,而不是加宽输入法窗口。
        val h = cursorRowHeightDp()
        val ts = (18f * h / rowHeightDp).coerceAtLeast(11f)
        fun key(text: String, weight: Float, onClick: () -> Unit) = makeKey(text, weight, h, ts, onClick)

        // 第一行:左上角 全选 / ↑ / 右上角 清除
        val row1 = newRow()
        row1.addView(key("全选", 1f) { onSelectAll() })
        row1.addView(key("↑", 1f) { moveCursor(KeyEvent.KEYCODE_DPAD_UP) })
        row1.addView(key("清除", 1f) { onClear() })
        panel.addView(row1)

        // 第二行:← / 选择 / →
        val row2 = newRow()
        row2.addView(key("←", 1f) { moveCursor(KeyEvent.KEYCODE_DPAD_LEFT) })
        val sk = key("选择", 1f) { toggleSelecting() }
        selectKey = sk
        row2.addView(sk)
        row2.addView(key("→", 1f) { moveCursor(KeyEvent.KEYCODE_DPAD_RIGHT) })
        panel.addView(row2)

        // 第三行:左下角 复制 / ↓ / 右下角 剪切
        val row3 = newRow()
        row3.addView(key("复制", 1f) { onClipAction(android.R.id.copy) })
        row3.addView(key("↓", 1f) { moveCursor(KeyEvent.KEYCODE_DPAD_DOWN) })
        row3.addView(key("剪切", 1f) { onClipAction(android.R.id.cut) })
        panel.addView(row3)

        // 第四行:|← / →|(选择模式下从光标扩展选区到本行行首/行末,类似 Home/End)
        val row4 = newRow()
        row4.addView(key("|←", 1f) { jumpToLineStart() })
        row4.addView(key("→|", 1f) { jumpToLineEnd() })
        panel.addView(row4)

        // 第五行:↑|| / ↓||(选择模式下从光标扩展选区到文本最开头/最末尾,类似 Ctrl+Home/End)
        val row5 = newRow()
        row5.addView(key("←||", 1f) { jumpToTop() })
        row5.addView(key("→||", 1f) { jumpToBottom() })
        panel.addView(row5)

        // 最底一行:粘贴 / 返回键盘
        val row6 = newRow()
        row6.addView(key("粘贴", 1f) { onClipAction(android.R.id.paste) })
        row6.addView(key("⌨", 1f) { closeCursorPanel() })
        panel.addView(row6)

        return panel
    }

    private fun openCursorPanel() {
        if (buf.isNotEmpty()) { commitText(buf.replace("'", "")); clearBuf() }
        selecting = false
        updateSelectKey()
        keyboardView?.visibility = View.GONE
        panelView?.visibility = View.GONE
        cursorView?.visibility = View.VISIBLE
    }

    private fun closeCursorPanel() {
        cursorView?.visibility = View.GONE
        keyboardView?.visibility = View.VISIBLE
    }

    /** 打开云同步页(登录/比较/同步都在独立 Activity,输入法窗口里无法操作)。 */
    private fun openSync() {
        if (buf.isNotEmpty()) { commitText(buf.replace("'", "")); clearBuf() }
        val intent = android.content.Intent(this, SyncActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    /**
     * 移动光标。选择模式下左右键直接用 setSelection 精确扩展选区(锚点固定,
     * 移动另一端),比发带 Shift 的按键事件更可靠;上下键(跨行)仍用按键事件兜底。
     */
    private fun moveCursor(keyCode: Int) {
        val ic = currentInputConnection ?: return
        if (selecting && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            // 与锚点不同的那一端是「移动端」;选区折叠时两端都等于锚点
            val movingEnd = if (curSelEnd != selAnchor) curSelEnd else curSelStart
            val delta = if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
            val newEnd = (movingEnd + delta).coerceAtLeast(0)
            ic.setSelection(minOf(selAnchor, newEnd), maxOf(selAnchor, newEnd))
            return
        }
        // 选择模式下上/下键:按行扩展选区(尽量保留列位置)。成功则返回,
        // 取不到整段文本时(部分编辑框不支持)再走下面的按键事件兜底。
        if (selecting && (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) {
            if (selectByLine(ic, keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) return
        }
        val meta = if (selecting) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
        val now = System.currentTimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta))
    }

    /**
     * 选择模式下按行扩展选区:把「移动端」上移/下移一行(列位置尽量保持),
     * 再以锚点为固定端重设选区。返回是否成功(取不到整段文本时返回 false 交给兜底)。
     */
    private fun selectByLine(ic: android.view.inputmethod.InputConnection, down: Boolean): Boolean {
        val et = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return false
        val text = et.text?.toString() ?: return false
        val base = et.startOffset.coerceAtLeast(0)            // 整段文本在原文中的起始偏移
        val movingEnd = if (curSelEnd != selAnchor) curSelEnd else curSelStart
        val pos = (movingEnd - base).coerceIn(0, text.length) // 移动端在整段文本中的下标
        val lineStart = text.lastIndexOf('\n', pos - 1).let { if (it < 0) 0 else it + 1 }
        val column = pos - lineStart
        val target: Int = if (down) {
            val lineEnd = text.indexOf('\n', pos).let { if (it < 0) text.length else it }
            if (lineEnd >= text.length) text.length            // 已是最后一行:选到末尾
            else {
                val nextStart = lineEnd + 1
                val nextEnd = text.indexOf('\n', nextStart).let { if (it < 0) text.length else it }
                minOf(nextStart + column, nextEnd)
            }
        } else {
            if (lineStart == 0) 0                              // 已是第一行:选到开头
            else {
                val prevEnd = lineStart - 1                     // 上一行末尾的换行符位置
                val prevStart = text.lastIndexOf('\n', prevEnd - 1).let { if (it < 0) 0 else it + 1 }
                minOf(prevStart + column, prevEnd)
            }
        }
        val g = target + base
        ic.setSelection(minOf(selAnchor, g), maxOf(selAnchor, g))
        return true
    }

    /**
     * 跳到当前行行首(类似 Home)。选择模式下以锚点为固定端扩展选区到行首,
     * 否则直接把光标折叠到行首。
     */
    private fun jumpToLineStart() {
        val ic = currentInputConnection ?: return
        val et = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return
        val text = et.text?.toString() ?: return
        val base = et.startOffset.coerceAtLeast(0)
        val ref = if (selecting) (if (curSelEnd != selAnchor) curSelEnd else curSelStart) else curSelStart
        val pos = (ref - base).coerceIn(0, text.length)
        val lineStart = text.lastIndexOf('\n', pos - 1).let { if (it < 0) 0 else it + 1 }
        val target = lineStart + base
        if (selecting) {
            ic.setSelection(minOf(selAnchor, target), maxOf(selAnchor, target))
        } else {
            ic.setSelection(target, target)
        }
    }

    /**
     * 跳到当前行行末(类似 End)。选择模式下以锚点为固定端扩展选区到行末,
     * 否则直接把光标折叠到行末。
     */
    private fun jumpToLineEnd() {
        val ic = currentInputConnection ?: return
        val et = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return
        val text = et.text?.toString() ?: return
        val base = et.startOffset.coerceAtLeast(0)
        val ref = if (selecting) (if (curSelEnd != selAnchor) curSelEnd else curSelStart) else curSelStart
        val pos = (ref - base).coerceIn(0, text.length)
        val lineEnd = text.indexOf('\n', pos).let { if (it < 0) text.length else it }
        val target = lineEnd + base
        if (selecting) {
            ic.setSelection(minOf(selAnchor, target), maxOf(selAnchor, target))
        } else {
            ic.setSelection(target, target)
        }
    }

    /**
     * 跳到文本开头(类似 Ctrl+Home)。选择模式下以锚点为固定端扩展选区到开头,
     * 否则直接把光标折叠到开头。
     */
    private fun jumpToTop() {
        val ic = currentInputConnection ?: return
        if (selecting) {
            ic.setSelection(minOf(selAnchor, 0), maxOf(selAnchor, 0))
        } else {
            ic.setSelection(0, 0)
        }
    }

    /**
     * 跳到文本末尾(类似 Ctrl+End)。选择模式下以锚点为固定端扩展选区到末尾,
     * 否则直接把光标折叠到末尾。
     */
    private fun jumpToBottom() {
        val ic = currentInputConnection ?: return
        val et = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return
        val text = et.text ?: return
        val end = et.startOffset.coerceAtLeast(0) + text.length
        if (selecting) {
            ic.setSelection(minOf(selAnchor, end), maxOf(selAnchor, end))
        } else {
            ic.setSelection(end, end)
        }
    }

    private fun toggleSelecting() {
        selecting = !selecting
        // 开启选择模式时,把当前光标位置定为锚点(后续移动以此端为固定端)
        if (selecting) selAnchor = curSelStart
        updateSelectKey()
    }

    /** 刷新「选择」键外观:选择模式下高亮显示。 */
    private fun updateSelectKey() {
        val k = selectKey ?: return
        k.text = if (selecting) "选择中" else "选择"
        k.background = GradientDrawable().apply {
            setColor(if (selecting) Color.parseColor("#4A90D9") else colSurface())
            cornerRadius = dp(6).toFloat()
        }
        k.setTextColor(if (selecting) Color.WHITE else colText())
    }

    private fun onSelectAll() {
        currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
        selecting = true
        updateSelectKey()
    }

    /** 执行剪切/复制/粘贴,完成后退出选择模式。 */
    private fun onClipAction(action: Int) {
        currentInputConnection?.performContextMenuAction(action)
        selecting = false
        updateSelectKey()
    }

    /** 清除:有选区时用空串替换选中内容即删除(配合「全选」可清空输入框);完成后退出选择模式。 */
    private fun onClear() {
        currentInputConnection?.commitText("", 1)
        selecting = false
        updateSelectKey()
    }

    private fun toggleMode() {
        if (buf.isNotEmpty()) { commitText(buf.replace("'", "")); clearBuf() }
        cnMode = !cnMode
        modeKey?.text = if (cnMode) "中" else "EN"
        updateLetterCaps()
    }

    private fun commitText(t: String) {
        currentInputConnection?.commitText(t, 1)
    }

    private fun writeBack(word: String, key: String, weight: Int) {
        val f = dictFile
        writeExec.execute {
            try { PinyinDict.updateDictFile(f, word, key, weight) } catch (e: Exception) { /* 忽略 */ }
        }
    }

    // ---------------------------------------------------------------- 刷新候选 UI
    private fun updatePreview() {
        pinyinPreview?.text = segs.joinToString("'")
        val container = candidatesContainer ?: return
        container.removeAllViews()
        if (dict == null && buf.isNotEmpty()) {
            container.addView(hintView("词库未加载:请把 pinyin_simp.dict.yaml 放到 1/IME_Yaml/D_IME_Yaml 目录并授予文件权限"))
        } else {
            for ((i, c) in cands.withIndex()) {
                container.addView(candView(c.word, i))
            }
        }
        val show = buf.isNotEmpty()
        // 有拼音输入:显示预览+候选;无输入:显示常驻工具条。两者总高相同,键盘不跳动。
        pinyinPreview?.visibility = if (show) View.VISIBLE else View.GONE
        candidatesScroll?.visibility = if (show) View.VISIBLE else View.GONE
        toolbarRow?.visibility = if (show) View.GONE else View.VISIBLE
        candidatesScroll?.scrollTo(0, 0)
    }

    private fun candView(word: String, index: Int): TextView {
        return TextView(this).apply {
            text = if (index < 9) "${index + 1} $word" else word
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setTextColor(colText())
            isClickable = true
            setOnClickListener { choose(index) }
        }
    }

    private fun hintView(msg: String): TextView {
        return TextView(this).apply {
            text = msg
            textSize = 13f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setTextColor(Color.parseColor("#B00020"))
        }
    }

    override fun onStartInput(attribute: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        clearBuf()
    }

    /** 编辑框选区变化回调:缓存光标/选区位置,供选择模式精确扩展选区。 */
    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd
        )
        curSelStart = newSelStart
        curSelEnd = newSelEnd
    }

    override fun onDestroy() {
        cancelPendingRecognition()
        try { recognizer?.close() } catch (e: Exception) { /* 忽略 */ }
        writeExec.shutdown()
        super.onDestroy()
    }

    companion object {
        const val PREFS = "pyime"
        const val KEY_ROW_HEIGHT = "row_height_dp"
        const val KEY_TOOL_ORDER = "toolbar_order"   // 工具按钮顺序(逗号分隔的 id)
        const val KEY_SYMBOL_ORDER = "symbol_order"  // 符号最近使用顺序(逗号分隔)
        const val TOOLBAR_COLS = 6                    // 顶栏网格列数
        const val TOOLBAR_ROWS = 2                    // 顶栏网格行数(2×6 共 12 格)
        const val TOOL_TOP_COUNT = TOOLBAR_COLS * TOOLBAR_ROWS  // 顶栏网格可放的按钮数(含展开按钮),其余进展开面板

        // —— 顶部区域(候选区 / 工具条)统一高度,避免两者切换时键盘跳动闪屏 ——
        const val PREVIEW_HEIGHT = 32                 // 拼音预览行高
        const val TOOLBAR_BTN_HEIGHT = 44             // 工具条按钮高
        const val TOOLBAR_BTN_MARGIN = 3              // 工具条按钮上下外间距
        const val TOOLBAR_ROW_HEIGHT = TOOLBAR_BTN_HEIGHT + TOOLBAR_BTN_MARGIN * 2  // 工具条单行高 = 50
        const val HEADER_HEIGHT = TOOLBAR_ROWS * TOOLBAR_ROW_HEIGHT                 // 工具条总高(最高者)= 100
        const val CANDIDATES_HEIGHT = HEADER_HEIGHT - PREVIEW_HEIGHT               // 候选栏高,使预览+候选 = HEADER_HEIGHT
        const val DEFAULT_ROW_HEIGHT = 46
        const val MIN_ROW_HEIGHT = 36
        const val MAX_ROW_HEIGHT = 76
        const val TOOL_TAB_ROW_HEIGHT = 34             // 工具面板顶部「剪贴板/常用语/同步」Tab 行高(比普通键位行矮)

        const val SHIFT_OFF = 0    // 小写
        const val SHIFT_ONCE = 1   // 单次大写(打一个字母后复位)
        const val SHIFT_LOCK = 2   // 大写锁定

        const val TAB_CLIP = 0     // 工具面板:剪贴板
        const val TAB_PHRASE = 1   // 工具面板:常用语
    }
}
