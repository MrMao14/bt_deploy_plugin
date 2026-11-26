package com.mrmao14.btdeploy.ui

import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.mrmao14.btdeploy.api.BtClient
import com.mrmao14.btdeploy.api.RemoteFile
import com.mrmao14.btdeploy.settings.BtDeploySettings.TargetConfig
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Rectangle
import java.awt.event.ActionEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.file.Files
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
import javax.swing.KeyStroke
import javax.swing.Timer

/** 查看服务器项目日志，使用 IDEA 原生控制台高亮和搜索框。 */
class TargetLogDialog(
    private val project: Project,
    private val target: TargetConfig,
    private val client: BtClient,
) : DialogWrapper(project) {
    private val app = ApplicationManager.getApplication()
    private val logsDir = "${target.remoteDir.trimEnd('/')}/logs"
    private val fileCombo = JComboBox<String>()
    private val refreshButton = JButton("刷新")
    private val openInEditorButton = JButton("用 IDEA 打开")
    private val autoRefreshBox = JBCheckBox("自动刷新")
    private val softWrapBox = JBCheckBox("自动换行", true)
    private val fullscreenButton = JButton("全屏")
    private val statusLabel = JBLabel("正在读取日志列表…")
    private val logViewDisposable = Disposer.newDisposable()
    private val logView = createLogView(project)
    private val prevButton = JButton("<")
    private val nextButton = JButton(">")

    private var files: List<RemoteFile> = emptyList()
    private var currentFile: RemoteFile? = null
    private var currentPage = 1
    private var hasNext = false
    private var loading = false
    private var updatingFiles = false
    private var disposed = false
    private var fullscreen = false
    private var fullscreenBounds: Rectangle? = null

    private val autoRefreshTimer = Timer(5_000) { loadFile(page = 1) }
    private val logTokenRegex = Regex(
        """(?<timestamp>\b\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}(?:[.,]\d+)?\b)""" +
            """|(?<error>\b(?:ERROR|FATAL|SEVERE)\b|Exception|Caused by)""" +
            """|(?<warning>\bWARN(?:ING)?\b)""" +
            """|(?<info>\b(?:INFO|INFORMATION)\b)""" +
            """|(?<debug>\b(?:DEBUG|TRACE|VERBOSE)\b)""",
        RegexOption.IGNORE_CASE,
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
        Disposer.register(logViewDisposable, logView)
        init()
        window.addWindowListener(object : WindowAdapter() {
            override fun windowOpened(e: WindowEvent) {
                applySoftWrap()
                refreshFiles()
            }
        })
        setSize(980, 680)
        isResizable = true

        installShortcuts()
        refreshButton.addActionListener { refreshFiles() }
        fileCombo.addActionListener {
            if (!updatingFiles) {
                currentFile = files.getOrNull(fileCombo.selectedIndex)
                loadFile()
            }
        }
        prevButton.addActionListener { loadFile(currentPage - 1) }
        nextButton.addActionListener { loadFile(currentPage + 1) }
        autoRefreshBox.addActionListener {
            if (autoRefreshBox.isSelected) autoRefreshTimer.start() else autoRefreshTimer.stop()
        }
        softWrapBox.addActionListener { applySoftWrap() }
        fullscreenButton.addActionListener { toggleFullscreen() }
        openInEditorButton.addActionListener { openInEditor() }
    }

    override fun createCenterPanel(): JComponent {
        fileCombo.toolTipText = "选择要查看的服务器日志文件"
        refreshButton.toolTipText = "重新读取日志文件列表"
        openInEditorButton.toolTipText = "下载为临时 .log 文件，用 IDEA 原生日志语法高亮和搜索打开"
        autoRefreshBox.toolTipText = "每 5 秒刷新当前日志"
        softWrapBox.toolTipText = "自动换行；关闭后可左右滚动查看完整日志行"
        fullscreenButton.toolTipText = "全屏查看（F11）"
        logView.component.toolTipText = "使用 IDEA 原生控制台显示日志，支持滚轮滚动和 Ctrl+F 搜索"
        prevButton.toolTipText = "查看更新的日志分段"
        nextButton.toolTipText = "查看更早的日志分段"
        statusLabel.foreground = JBColor.GRAY

        val directoryLabel = JBLabel("日志目录：$logsDir").apply { foreground = JBColor.GRAY }
        val fileTools = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            add(refreshButton)
            add(openInEditorButton)
            add(autoRefreshBox)
            add(softWrapBox)
        }
        val selector = JPanel(BorderLayout(6, 0)).apply {
            add(fileCombo, BorderLayout.CENTER)
            add(fileTools, BorderLayout.EAST)
        }
        val top = JPanel(BorderLayout(0, 4)).apply {
            add(directoryLabel, BorderLayout.NORTH)
            add(selector, BorderLayout.CENTER)
        }
        val pager = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            add(prevButton)
            add(nextButton)
            add(fullscreenButton)
        }
        val bottom = JPanel(BorderLayout()).apply {
            add(statusLabel, BorderLayout.CENTER)
            add(pager, BorderLayout.EAST)
        }

        return JPanel(BorderLayout(0, 6)).apply {
            border = JBUI.Borders.empty(8)
            add(top, BorderLayout.NORTH)
            add(logView.component, BorderLayout.CENTER)
            add(bottom, BorderLayout.SOUTH)
            preferredSize = Dimension(960, 620)
        }.also { applySoftWrap() }
    }

    override fun createActions(): Array<Action> = arrayOf(downloadAction, closeAction)

    /** 打开时把焦点交给日志控制台，避免「自动换行」复选框首次显示成焦点高亮。 */
    override fun getPreferredFocusedComponent(): JComponent = logView.component

    override fun dispose() {
        disposed = true
        autoRefreshTimer.stop()
        Disposer.dispose(logViewDisposable)
        super.dispose()
    }

    private fun installShortcuts() {
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).apply {
            put(KeyStroke.getKeyStroke("F11"), "toggleLogFullscreen")
        }
        rootPane.actionMap.put("toggleLogFullscreen", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                toggleFullscreen()
            }
        })
    }

    private fun applySoftWrap() {
        (logView as? ConsoleViewImpl)?.editor?.settings?.isUseSoftWraps = softWrapBox.isSelected
    }

    /** ConsoleView 默认 1MB 循环缓冲会先丢弃最新日志，创建本控制台时临时关闭。 */
    private fun createLogView(project: Project) = run {
        val property = "idea.cycle.buffer.size"
        val previous = System.getProperty(property)
        System.setProperty(property, "disabled")
        try {
            TextConsoleBuilderFactory.getInstance()
                .createBuilder(project)
                .apply {
                    setViewer(true)
                    setUseOwnModalityStateForUpdates(true)
                }
                .console
                .also { it.component }
        } finally {
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
        }
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
                    setLogText(if (largeFile) body.text else newestFirst(body.text))
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

    private fun openInEditor() {
        val file = currentFile ?: return
        loading = true
        updateControls()
        statusLabel.text = "正在准备 ${file.name}…"
        app.executeOnPooledThread {
            val result = runCatching {
                val dir = Paths.get(PathManager.getTempPath(), "bt-deploy-logs", safeName(target.name))
                Files.createDirectories(dir)
                val localPath = dir.resolve(safeName(file.name))
                client.downloadFile("$logsDir/${file.name}", localPath)
                LocalFileSystem.getInstance().refreshAndFindFileByPath(localPath.toString())
            }
            app.invokeLater({
                if (disposed) return@invokeLater
                loading = false
                updateControls()
                result.fold(
                    onSuccess = { virtualFile ->
                        if (virtualFile == null) {
                            statusLabel.text = "无法在 IDEA 中打开 ${file.name}"
                        } else {
                            FileEditorManager.getInstance(project).openFile(virtualFile, true)
                            statusLabel.text = "已在 IDEA 中打开 ${file.name}（临时副本）"
                        }
                    },
                    onFailure = { statusLabel.text = "打开 ${file.name} 失败：${it.message?.take(100) ?: "未知错误"}" },
                )
            }, ModalityState.any())
        }
    }

    private fun setLogText(text: String) {
        logView.clear()
        var contentType = ConsoleViewContentType.NORMAL_OUTPUT
        val chunk = StringBuilder()

        fun append(value: String, type: ConsoleViewContentType) {
            if (value.isEmpty()) return
            if (type !== contentType && chunk.isNotEmpty()) {
                logView.print(chunk.toString(), contentType)
                chunk.clear()
            }
            contentType = type
            chunk.append(value)
        }

        normalizeLineEndings(text).lineSequence().forEach { line ->
            var offset = 0
            logTokenRegex.findAll(line).forEach { match ->
                append(line.substring(offset, match.range.first), ConsoleViewContentType.NORMAL_OUTPUT)
                append(match.value, logContentType(match))
                offset = match.range.last + 1
            }
            append(line.substring(offset), ConsoleViewContentType.NORMAL_OUTPUT)
            append("\n", ConsoleViewContentType.NORMAL_OUTPUT)
        }
        if (chunk.isNotEmpty()) logView.print(chunk.toString(), contentType)
        logView.performWhenNoDeferredOutput { logView.scrollTo(0) }
    }

    private fun logContentType(match: MatchResult): ConsoleViewContentType = when {
        match.groups["timestamp"] != null -> ConsoleViewContentType.SYSTEM_OUTPUT
        match.groups["error"] != null -> ConsoleViewContentType.LOG_ERROR_OUTPUT
        match.groups["warning"] != null -> ConsoleViewContentType.LOG_WARNING_OUTPUT
        match.groups["info"] != null -> ConsoleViewContentType.LOG_INFO_OUTPUT
        else -> ConsoleViewContentType.LOG_DEBUG_OUTPUT
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
        openInEditorButton.isEnabled = idle && currentFile != null
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

    private fun safeName(name: String): String = name.replace(Regex("""[\\/:*?"<>|]"""), "_")

    private fun formatSize(size: Long): String = when {
        size >= 1024 * 1024 -> "%.1f MB".format(Locale.ROOT, size / 1024.0 / 1024.0)
        size >= 1024 -> "%.1f KB".format(Locale.ROOT, size / 1024.0)
        else -> "$size B"
    }

    private fun time(): String = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())

    private companion object {
        const val MAX_READ_SIZE = 3L * 1024 * 1024
    }
}
