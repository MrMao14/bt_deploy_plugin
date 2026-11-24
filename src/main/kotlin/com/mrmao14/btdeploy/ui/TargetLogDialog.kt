package com.mrmao14.btdeploy.ui

import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.mrmao14.btdeploy.api.BtClient
import com.mrmao14.btdeploy.api.RemoteFile
import com.mrmao14.btdeploy.settings.BtDeploySettings.TargetConfig
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import java.awt.Rectangle
import java.awt.event.ActionEvent
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.KeyStroke
import javax.swing.Scrollable
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.DefaultHighlighter
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

/** 查看服务器项目日志，使用 IDEA 控制台配色并支持搜索、字号和全屏。 */
class TargetLogDialog(
    private val project: Project,
    private val target: TargetConfig,
    private val client: BtClient,
) : DialogWrapper(project) {
    private val app = ApplicationManager.getApplication()
    private val logsDir = "${target.remoteDir.trimEnd('/')}/logs"
    private val fileCombo = JComboBox<String>()
    private val refreshButton = JButton("刷新")
    private val autoRefreshBox = JBCheckBox("自动刷新")
    private val searchField = JBTextField()
    private val searchPrevButton = JButton("上一个")
    private val searchNextButton = JButton("下一个")
    private val hitsLabel = JBLabel()
    private val decreaseFontButton = JButton("A−")
    private val increaseFontButton = JButton("A+")
    private val fullscreenButton = JButton("全屏")
    private val statusLabel = JBLabel("正在读取日志列表…")
    private val logPane = LogTextPane()
    private val prevButton = JButton("<")
    private val nextButton = JButton(">")

    private var files: List<RemoteFile> = emptyList()
    private var currentFile: RemoteFile? = null
    private var currentPage = 1
    private var hasNext = false
    private var loading = false
    private var updatingFiles = false
    private var disposed = false
    private var fontSize = 13
    private var fullscreen = false
    private var fullscreenBounds: Rectangle? = null
    private var searchHits: List<IntRange> = emptyList()
    private var searchIndex = -1
    private var searchLimitReached = false
    private var currentSearchHighlight: Any? = null

    private val autoRefreshTimer = Timer(5_000) { loadFile(page = 1) }
    private val searchTimer = Timer(250) { rebuildSearchHighlights() }.apply { isRepeats = false }
    private val searchPainter = DefaultHighlighter.DefaultHighlightPainter(
        JBColor(0xD6E8FF, 0x264F78),
    )
    private val currentSearchPainter = DefaultHighlighter.DefaultHighlightPainter(
        JBColor(0xFFE082, 0x806000),
    )
    private val normalAttributes = logAttributes(ConsoleViewContentType.NORMAL_OUTPUT)
    private val logStyleRules = listOf(
        Regex("""\b(?:ERROR|FATAL|SEVERE)\b|Exception|Caused by""", RegexOption.IGNORE_CASE) to
            logAttributes(ConsoleViewContentType.LOG_ERROR_OUTPUT),
        Regex("""\bWARN(?:ING)?\b""", RegexOption.IGNORE_CASE) to
            logAttributes(ConsoleViewContentType.LOG_WARNING_OUTPUT),
        Regex("""\b(?:INFO|INFORMATION)\b""", RegexOption.IGNORE_CASE) to
            logAttributes(ConsoleViewContentType.LOG_INFO_OUTPUT),
        Regex("""\b(?:DEBUG|TRACE|VERBOSE)\b""", RegexOption.IGNORE_CASE) to
            logAttributes(ConsoleViewContentType.LOG_DEBUG_OUTPUT),
        Regex("""\b\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}(?:[.,]\d+)?\b""") to
            logAttributes(ConsoleViewContentType.SYSTEM_OUTPUT),
    )

    private val downloadAction = object : AbstractAction("下载日志") {
        override fun actionPerformed(e: ActionEvent) {
            downloadCurrentFile()
        }
    }.apply {
        isEnabled = false
    }

    private val closeAction = object : AbstractAction("关闭") {
        override fun actionPerformed(e: ActionEvent) {
            close(CANCEL_EXIT_CODE)
        }
    }

    init {
        title = "项目日志 - ${target.name}"
        init()
        setSize(980, 680)
        isResizable = true

        installShortcuts()
        refreshButton.addActionListener { refreshFiles() }
        fileCombo.addActionListener { if (!updatingFiles) loadFile() }
        prevButton.addActionListener { loadFile(currentPage - 1) }
        nextButton.addActionListener { loadFile(currentPage + 1) }
        autoRefreshBox.addActionListener {
            if (autoRefreshBox.isSelected) autoRefreshTimer.start() else autoRefreshTimer.stop()
        }
        searchField.addActionListener { findNext() }
        searchNextButton.addActionListener { findNext() }
        searchPrevButton.addActionListener { findPrevious() }
        decreaseFontButton.addActionListener { changeFontSize(-1) }
        increaseFontButton.addActionListener { changeFontSize(1) }
        fullscreenButton.addActionListener { toggleFullscreen() }
        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = scheduleSearch()
            override fun removeUpdate(e: DocumentEvent) = scheduleSearch()
            override fun changedUpdate(e: DocumentEvent) = scheduleSearch()
        })
        applyFontSize()
        refreshFiles()
    }

    override fun createCenterPanel(): JComponent {
        fileCombo.toolTipText = "选择要查看的服务器日志文件"
        refreshButton.toolTipText = "重新读取日志文件列表"
        autoRefreshBox.toolTipText = "每 5 秒刷新当前日志"
        searchField.emptyText.text = "搜索日志（Ctrl+F）"
        searchPrevButton.toolTipText = "上一个匹配项"
        searchNextButton.toolTipText = "下一个匹配项"
        decreaseFontButton.toolTipText = "减小日志字号"
        increaseFontButton.toolTipText = "增大日志字号"
        fullscreenButton.toolTipText = "全屏查看（F11）"
        prevButton.toolTipText = "查看更新的日志分段"
        nextButton.toolTipText = "查看更早的日志分段"
        hitsLabel.foreground = JBColor.GRAY
        statusLabel.foreground = JBColor.GRAY
        logPane.apply {
            isEditable = false
            background = EditorColorsManager.getInstance().globalScheme
                .getColor(ConsoleViewContentType.CONSOLE_BACKGROUND_KEY)
                ?: UIUtil.getPanelBackground()
        }

        val directoryLabel = JBLabel("日志目录：$logsDir").apply { foreground = JBColor.GRAY }
        val fileTools = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            add(refreshButton)
            add(autoRefreshBox)
        }
        val selector = JPanel(BorderLayout(6, 0)).apply {
            add(fileCombo, BorderLayout.CENTER)
            add(fileTools, BorderLayout.EAST)
        }
        val searchTools = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(searchPrevButton)
            add(searchNextButton)
            add(hitsLabel)
            add(decreaseFontButton)
            add(increaseFontButton)
            add(fullscreenButton)
        }
        val searchBar = JPanel(BorderLayout(6, 0)).apply {
            add(searchField, BorderLayout.CENTER)
            add(searchTools, BorderLayout.EAST)
        }
        val top = JPanel(BorderLayout(0, 4)).apply {
            add(directoryLabel, BorderLayout.NORTH)
            add(JPanel(GridLayout(2, 1, 0, 4)).apply {
                add(selector)
                add(searchBar)
            }, BorderLayout.CENTER)
        }
        val pager = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            add(prevButton)
            add(nextButton)
        }
        val bottom = JPanel(BorderLayout()).apply {
            add(statusLabel, BorderLayout.CENTER)
            add(pager, BorderLayout.EAST)
        }

        return JPanel(BorderLayout(0, 6)).apply {
            border = JBUI.Borders.empty(8)
            add(top, BorderLayout.NORTH)
            add(JBScrollPane(logPane), BorderLayout.CENTER)
            add(bottom, BorderLayout.SOUTH)
            preferredSize = Dimension(960, 620)
        }
    }

    override fun createActions(): Array<Action> = arrayOf(downloadAction, closeAction)

    override fun dispose() {
        disposed = true
        autoRefreshTimer.stop()
        searchTimer.stop()
        super.dispose()
    }

    private fun installShortcuts() {
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).apply {
            put(KeyStroke.getKeyStroke("control F"), "focusLogSearch")
            put(KeyStroke.getKeyStroke("F11"), "toggleLogFullscreen")
        }
        rootPane.actionMap.apply {
            put("focusLogSearch", object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) {
                    searchField.requestFocusInWindow()
                    searchField.selectAll()
                }
            })
            put("toggleLogFullscreen", object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) {
                    toggleFullscreen()
                }
            })
        }
        searchField.inputMap.put(KeyStroke.getKeyStroke("shift ENTER"), "previousLogHit")
        searchField.actionMap.put("previousLogHit", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                findPrevious()
            }
        })
    }

    private fun refreshFiles() {
        if (loading || disposed) return
        loading = true
        updateControls()
        statusLabel.text = "正在读取日志文件列表…"

        app.executeOnPooledThread {
            val result = runCatching { client.listDir(logsDir).sortedByDescending { it.mtime } }
            app.invokeLater({
                if (disposed) return@invokeLater
                loading = false
                result.onSuccess(::applyFiles).onFailure {
                    files = emptyList()
                    currentFile = null
                    showFiles()
                    setLogText("读取日志文件列表失败：${it.message?.take(200) ?: "未知错误"}")
                    statusLabel.text = "读取日志文件列表失败"
                }
                updateControls()
            }, ModalityState.any())
        }
    }

    private fun applyFiles(loaded: List<RemoteFile>) {
        val selectedName = currentFile?.name
        files = loaded
        showFiles()
        val selected = files.firstOrNull { it.name == selectedName } ?: files.firstOrNull()
        currentFile = selected
        if (selected == null) {
            setLogText("日志目录中没有日志文件：$logsDir")
            statusLabel.text = "日志目录中没有日志文件"
            return
        }

        updatingFiles = true
        fileCombo.selectedItem = selected.name
        updatingFiles = false
        statusLabel.text = "${files.size} 个文件 · $logsDir"
        loadFile(page = 1)
    }

    private fun showFiles() {
        updatingFiles = true
        fileCombo.removeAllItems()
        files.forEach { fileCombo.addItem(it.name) }
        updatingFiles = false
    }

    private fun loadFile(page: Int = 1) {
        if (loading || disposed) return
        val file = currentFile ?: return
        val largeFile = file.size > MAX_READ_SIZE
        if (largeFile && page < 1) return

        currentPage = if (largeFile) page else 1
        loading = true
        updateControls()
        statusLabel.text = "正在读取 ${file.name}…"
        val mode = if (largeFile) "reverse" else null
        val requestedPage = currentPage

        app.executeOnPooledThread {
            val result = runCatching {
                client.readFile("$logsDir/${file.name}", mode, requestedPage)
            }
            app.invokeLater({
                if (disposed) return@invokeLater
                loading = false
                result.onSuccess { body ->
                    setLogText(if (requestedPage > 1) body.text else newestFirst(body.text))
                    hasNext = body.hasNext
                    val pageText = if (largeFile) " · 第 $requestedPage 段" else ""
                    statusLabel.text =
                        "${file.name} · ${formatSize(file.size)} · 刷新于 ${time()}$pageText"
                }.onFailure {
                    hasNext = false
                    statusLabel.text = "读取 ${file.name} 失败：${it.message?.take(100) ?: "未知错误"}"
                }
                updateControls()
            }, ModalityState.any())
        }
    }

    private fun downloadCurrentFile() {
        val file = currentFile ?: return
        val descriptor = FileSaverDescriptor("下载项目日志", "选择日志保存位置")
        val wrapper = FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)
            .save(file.name) ?: return
        val virtualFile = wrapper.getVirtualFile(true) ?: return
        val localPath = Paths.get(virtualFile.path)

        loading = true
        updateControls()
        statusLabel.text = "正在下载 ${file.name}…"
        app.executeOnPooledThread {
            val result = runCatching { client.downloadFile("$logsDir/${file.name}", localPath) }
            app.invokeLater({
                if (disposed) return@invokeLater
                loading = false
                statusLabel.text = result.fold(
                    onSuccess = { "已下载到 ${virtualFile.path}" },
                    onFailure = { "下载 ${file.name} 失败：${it.message?.take(100) ?: "未知错误"}" },
                )
                updateControls()
            }, ModalityState.any())
        }
    }

    private fun setLogText(text: String) {
        logPane.styledDocument.apply {
            remove(0, length)
            insertString(0, normalizeLineEndings(text), normalAttributes)
        }
        applyLogHighlight()
        logPane.caretPosition = 0
        rebuildSearchHighlights()
    }

    private fun applyLogHighlight() {
        val document = logPane.styledDocument
        val text = document.getText(0, document.length)
        logStyleRules.forEach { (regex, attributes) ->
            regex.findAll(text).forEach {
                document.setCharacterAttributes(it.range.first, it.value.length, attributes, false)
            }
        }
    }

    private fun scheduleSearch() {
        searchTimer.restart()
    }

    private fun rebuildSearchHighlights() {
        if (disposed) return
        logPane.highlighter.removeAllHighlights()
        currentSearchHighlight = null
        searchHits = emptyList()
        searchIndex = -1
        searchLimitReached = false

        val term = searchField.text.trim()
        if (term.isEmpty()) {
            hitsLabel.text = ""
            return
        }
        val document = logPane.document
        val matches = Regex(Regex.escape(term), RegexOption.IGNORE_CASE)
            .findAll(document.getText(0, document.length))
            .map { it.range }
            .take(MAX_SEARCH_HITS + 1)
            .toList()
        searchLimitReached = matches.size > MAX_SEARCH_HITS
        searchHits = matches.take(MAX_SEARCH_HITS)
        if (searchHits.isEmpty()) {
            hitsLabel.text = "0/0"
            return
        }

        searchHits.forEach {
            logPane.highlighter.addHighlight(it.first, it.last + 1, searchPainter)
        }
        searchIndex = 0
        selectSearchHit()
    }

    private fun findNext() {
        moveSearchHit(1)
    }

    private fun findPrevious() {
        moveSearchHit(-1)
    }

    private fun moveSearchHit(step: Int) {
        if (searchHits.isEmpty()) {
            rebuildSearchHighlights()
            return
        }
        searchIndex = (searchIndex + step + searchHits.size) % searchHits.size
        selectSearchHit()
    }

    private fun selectSearchHit() {
        val hit = searchHits.getOrNull(searchIndex) ?: return
        currentSearchHighlight?.let(logPane.highlighter::removeHighlight)
        currentSearchHighlight = logPane.highlighter.addHighlight(
            hit.first,
            hit.last + 1,
            currentSearchPainter,
        )
        hitsLabel.text = "${searchIndex + 1}/${searchHits.size}${if (searchLimitReached) "+" else ""}"
        logPane.select(hit.first, hit.last + 1)
        logPane.modelToView2D(hit.first)?.let { logPane.scrollRectToVisible(it.bounds) }
    }

    private fun changeFontSize(delta: Int) {
        fontSize = (fontSize + delta).coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
        applyFontSize()
    }

    private fun applyFontSize() {
        logPane.font = Font(Font.MONOSPACED, Font.PLAIN, fontSize)
    }

    private fun toggleFullscreen() {
        val dialogWindow = window ?: return
        if (fullscreen) {
            fullscreenBounds?.let { dialogWindow.bounds = it }
            fullscreen = false
            fullscreenButton.text = "全屏"
        } else {
            fullscreenBounds = dialogWindow.bounds
            dialogWindow.bounds = dialogWindow.graphicsConfiguration.bounds
            fullscreen = true
            fullscreenButton.text = "退出全屏"
        }
    }

    private fun updateControls() {
        val idle = !loading
        fileCombo.isEnabled = idle && files.isNotEmpty()
        refreshButton.isEnabled = idle
        autoRefreshBox.isEnabled = idle
        prevButton.isEnabled = idle && currentPage > 1
        nextButton.isEnabled = idle && hasNext
        downloadAction.isEnabled = idle && currentFile != null
        if (!autoRefreshBox.isSelected) autoRefreshTimer.stop()
    }

    private fun newestFirst(text: String): String =
        if (text.isEmpty()) text else text.split('\n').asReversed().joinToString("\n")

    private fun normalizeLineEndings(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n')

    private fun formatSize(size: Long): String = when {
        size >= 1024 * 1024 -> "%.1f MB".format(Locale.ROOT, size / 1024.0 / 1024.0)
        size >= 1024 -> "%.1f KB".format(Locale.ROOT, size / 1024.0)
        else -> "$size B"
    }

    private fun time(): String = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())

    private companion object {
        const val MAX_READ_SIZE = 10L * 1024 * 1024
        const val MAX_SEARCH_HITS = 2_000
        const val MIN_FONT_SIZE = 10
        const val MAX_FONT_SIZE = 28

        fun logAttributes(type: ConsoleViewContentType): SimpleAttributeSet =
            SimpleAttributeSet().apply {
                type.attributes.foregroundColor?.let { StyleConstants.setForeground(this, it) }
                val fontType = type.attributes.fontType
                StyleConstants.setBold(this, fontType and Font.BOLD != 0)
                StyleConstants.setItalic(this, fontType and Font.ITALIC != 0)
            }
    }

    private class LogTextPane : JTextPane(), Scrollable {
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = 16

        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
            visibleRect.height

        override fun getScrollableTracksViewportWidth(): Boolean = false

        override fun getScrollableTracksViewportHeight(): Boolean = false
    }
}
