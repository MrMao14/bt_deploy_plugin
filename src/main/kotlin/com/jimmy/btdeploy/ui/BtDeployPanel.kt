package com.jimmy.btdeploy.ui

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.task.ProjectTaskManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.jimmy.btdeploy.api.BtApiException
import com.jimmy.btdeploy.api.BtClient
import com.jimmy.btdeploy.deploy.DeployConfig
import com.jimmy.btdeploy.deploy.Deployer
import com.jimmy.btdeploy.deploy.RestartType
import com.jimmy.btdeploy.deploy.StatusService
import com.jimmy.btdeploy.deploy.TargetState
import com.jimmy.btdeploy.deploy.TargetStatus
import com.jimmy.btdeploy.settings.BtDeployLogStore
import com.jimmy.btdeploy.settings.BtDeployProjectSettings
import com.jimmy.btdeploy.settings.BtDeployProjectSettings.PanelConfig
import com.jimmy.btdeploy.settings.BtDeployProjectSettings.TargetConfig
import org.jetbrains.concurrency.Promise
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingConstants

class BtDeployPanel(private val project: Project) {
    private val app = ApplicationManager.getApplication()
    private val projectSettings = project.getService(BtDeployProjectSettings::class.java)
    private val logStore = project.getService(BtDeployLogStore::class.java)
    private val state get() = projectSettings.state

    private val panelCombo = JComboBox<PanelConfig>()
    private val refreshStatusButton = JButton("刷新", AllIcons.Actions.Refresh)
    private val settingsButton = JButton("设置", AllIcons.General.Settings)

    private val cardsBox = JPanel()
    private val logTitle = JBLabel("部署日志")
    private val logArea = JTextArea()
    private val clearLogButton = JButton("清空")

    private val busy = AtomicBoolean(false)
    private var statusLoading = false
    private var statusGeneration = 0
    private var targetStatuses: Map<String, TargetStatus> = emptyMap()
    private var shownLogTargetId: String? = null
    private var refreshingPanels = false

    val component: JComponent = buildUi()

    init {
        bindEvents()
        refreshPanels()
        refreshTargets()
        refreshStatuses()
    }

    private fun buildUi(): JComponent {
        refreshStatusButton.toolTipText = "刷新服务状态"
        refreshStatusButton.isFocusable = false
        settingsButton.toolTipText = "面板与部署配置管理"
        panelCombo.toolTipText = "选择面板"

        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            add(refreshStatusButton)
            add(settingsButton)
        }
        val top = JPanel(BorderLayout(6, 0)).apply {
            add(JBLabel("面板:"), BorderLayout.WEST)
            add(panelCombo, BorderLayout.CENTER)
            add(right, BorderLayout.EAST)
        }

        cardsBox.layout = BoxLayout(cardsBox, BoxLayout.Y_AXIS)
        cardsBox.background = UIUtil.getPanelBackground()
        val cardsWrapper = JPanel(BorderLayout()).apply {
            background = UIUtil.getPanelBackground()
            add(cardsBox, BorderLayout.NORTH)
        }
        val cardsScroll = JBScrollPane(cardsWrapper).apply {
            border = JBUI.Borders.empty()
            verticalScrollBar.unitIncrement = 16
        }

        logTitle.font = logTitle.font.deriveFont(Font.BOLD)
        logArea.apply {
            isEditable = false
            lineWrap = false
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        }
        clearLogButton.toolTipText = "清空当前配置的日志"
        val logHeader = JPanel(BorderLayout()).apply {
            add(logTitle, BorderLayout.WEST)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply { add(clearLogButton) }, BorderLayout.EAST)
        }
        val logPanel = JPanel(BorderLayout(0, 4)).apply {
            border = JBUI.Borders.emptyTop(6)
            add(logHeader, BorderLayout.NORTH)
            add(JBScrollPane(logArea), BorderLayout.CENTER)
            preferredSize = Dimension(0, 190)
        }

        return JPanel(BorderLayout(0, 8)).apply {
            border = JBUI.Borders.empty(8)
            add(top, BorderLayout.NORTH)
            add(cardsScroll, BorderLayout.CENTER)
            add(logPanel, BorderLayout.SOUTH)
            preferredSize = Dimension(440, 660)
        }
    }

    private fun bindEvents() {
        panelCombo.addActionListener {
            if (refreshingPanels) return@addActionListener
            val panel = panelCombo.selectedItem as? PanelConfig ?: return@addActionListener
            state.activePanelId = panel.id
            state.selectedTargetId = ""
            refreshTargets()
            refreshStatuses()
        }

        refreshStatusButton.addActionListener { refreshStatuses() }
        settingsButton.addActionListener { openSettings() }
        clearLogButton.addActionListener {
            selectedTarget()?.let { logStore.clear(it.id) }
            logArea.text = ""
        }
    }

    private fun openSettings() {
        if (busy.get()) return
        SettingsDialog(project, projectSettings, logStore, state.activePanelId).show()
        refreshPanels()
        refreshTargets()
        refreshStatuses()
    }

    private fun refreshPanels() {
        refreshingPanels = true
        panelCombo.removeAllItems()
        state.panels.forEach(panelCombo::addItem)
        val active = state.panels.firstOrNull { it.id == state.activePanelId } ?: state.panels.first()
        panelCombo.selectedItem = active
        state.activePanelId = active.id
        refreshingPanels = false
        updateTopActions()
    }

    private fun refreshTargets() {
        val panel = activePanel()
        val targets = if (panel == null) emptyList() else state.targets.filter { it.panelId == panel.id }
        if (targets.none { it.id == state.selectedTargetId }) {
            state.selectedTargetId = targets.firstOrNull()?.id.orEmpty()
        }

        cardsBox.removeAll()
        if (targets.isEmpty()) {
            cardsBox.add(JPanel(BorderLayout()).apply {
                preferredSize = Dimension(0, 80)
                maximumSize = Dimension(Int.MAX_VALUE, 80)
                add(
                    JBLabel("还没有部署配置，点右上角「设置」添加").apply {
                        horizontalAlignment = SwingConstants.CENTER
                        foreground = JBColor.GRAY
                    },
                    BorderLayout.CENTER,
                )
            })
        } else {
            targets.forEachIndexed { index, target ->
                if (index > 0) cardsBox.add(Box.createVerticalStrut(8))
                cardsBox.add(
                    TargetCard(
                        target = target,
                        status = targetStatuses[target.id],
                        selected = target.id == state.selectedTargetId,
                        enabled = !busy.get(),
                        onSelect = { selectTarget(target.id) },
                        onDeploy = {
                            selectTarget(target.id)
                            deploy(target, buildFirst = false)
                        },
                        onBuildDeploy = {
                            selectTarget(target.id)
                            deploy(target, buildFirst = true)
                        },
                        onEdit = {
                            selectTarget(target.id)
                            editTarget(target)
                        },
                        onDelete = { deleteTarget(target) },
                    )
                )
            }
        }

        cardsBox.revalidate()
        cardsBox.repaint()
        syncLogPanel()
        updateTopActions()
    }

    private fun updateTopActions() {
        val idle = !busy.get()
        panelCombo.isEnabled = idle
        settingsButton.isEnabled = idle
        refreshStatusButton.isEnabled = idle && !statusLoading
        clearLogButton.isEnabled = selectedTarget() != null
    }

    private fun selectTarget(id: String) {
        if (state.selectedTargetId == id) return
        state.selectedTargetId = id
        refreshTargets()
    }

    private fun activePanel(): PanelConfig? = state.panels.firstOrNull { it.id == state.activePanelId }

    private fun selectedTarget(): TargetConfig? {
        val panel = activePanel() ?: return null
        return state.targets.firstOrNull { it.id == state.selectedTargetId && it.panelId == panel.id }
    }

    private fun syncLogPanel() {
        val target = selectedTarget()
        logTitle.text = "部署日志 - ${target?.name ?: "未选择"}"
        clearLogButton.isEnabled = target != null

        val id = target?.id
        if (id != shownLogTargetId) {
            shownLogTargetId = id
            logArea.text = when {
                target == null -> NO_TARGET_TEXT
                else -> logStore.text(target.id).ifBlank { NO_LOG_TEXT }
            }
            logArea.caretPosition = logArea.document.length
        } else if (target == null && logArea.text.isEmpty()) {
            logArea.text = NO_TARGET_TEXT
        }
    }

    /** 当前配置的日志固定显示在底部，部署时实时追加。 */
    private fun appendLog(target: TargetConfig?, line: String) {
        val stamped = "${timestamp()}  $line"
        if (target != null) logStore.append(target.id, stamped)
        app.invokeLater {
            if (target != null && target.id == state.selectedTargetId) {
                if (logArea.text == NO_LOG_TEXT || logArea.text == NO_TARGET_TEXT) {
                    logArea.text = ""
                }
                logArea.append(stamped + "\n")
                logArea.caretPosition = logArea.document.length
            }
        }
    }

    private fun refreshStatuses() {
        val panel = activePanel()
        val targets = if (panel == null) emptyList() else state.targets.filter { it.panelId == panel.id }
        val generation = ++statusGeneration

        if (targets.isEmpty()) {
            statusLoading = false
            targetStatuses = emptyMap()
            refreshTargets()
            return
        }

        val url = panel?.panelUrl?.trim().orEmpty()
        val apiSk = panel?.apiSk?.trim().orEmpty()
        if (panel == null || url.isEmpty() || apiSk.isEmpty()) {
            statusLoading = false
            targetStatuses = fallbackStatuses(targets)
            refreshTargets()
            return
        }

        val client = try {
            BtClient(url, apiSk, panel.verifySsl, 15_000)
        } catch (e: BtApiException) {
            statusLoading = false
            targetStatuses = fallbackStatuses(targets)
            refreshTargets()
            return
        }

        statusLoading = true
        targetStatuses = emptyMap()
        refreshTargets()

        app.executeOnPooledThread {
            val result = StatusService.load(client, targets)
            app.invokeLater {
                if (generation != statusGeneration) return@invokeLater
                statusLoading = false
                targetStatuses = result
                refreshTargets()
            }
        }
    }

    private fun fallbackStatuses(targets: List<TargetConfig>): Map<String, TargetStatus> =
        targets.associate {
            val type = RestartType.from(it.restartType)
            it.id to TargetStatus(
                if (type == RestartType.NONE) TargetState.NOT_APPLICABLE else TargetState.UNKNOWN
            )
        }

    private fun deploy(target: TargetConfig, buildFirst: Boolean) {
        if (busy.get()) return
        val panel = activePanel() ?: return warn(target, "请先选择面板")
        if (target.localPaths.isEmpty()) return warn(target, "请先给部署配置添加本地路径")
        if (target.remoteDir.isBlank()) return warn(target, "请先填写远程目录")

        val client = try {
            BtClient(panel.panelUrl, panel.apiSk, panel.verifySsl)
        } catch (e: BtApiException) {
            warn(target, e.message ?: "面板配置有误")
            return
        }

        state.selectedTargetId = target.id
        refreshTargets()
        prepareLogFor(target)

        val config = DeployConfig(
            sources = target.localPaths.map { Paths.get(it) },
            remoteDir = target.remoteDir,
            keepRoot = target.keepRoot,
            restart = RestartType.from(target.restartType),
            serviceName = target.serviceName,
            projectName = target.javaProjectName,
        )
        val title = if (buildFirst) "打包并部署" else "部署"
        appendLog(target, "===== $title：${target.name} =====")

        runTask(title, target, if (buildFirst) "打包并部署完成 ✅" else "部署完成 ✅") { log ->
            if (buildFirst) {
                log("正在执行 IDEA Build…")
                buildProject()
                log("IDEA Build 完成")
            }
            Deployer.deploy(client, config, log)
        }
    }

    private fun prepareLogFor(target: TargetConfig) {
        shownLogTargetId = target.id
        logArea.text = logStore.text(target.id)
        logArea.caretPosition = logArea.document.length
    }

    /** 调用 IDEA 的 Build Project（构建全部模块），失败时抛异常阻止后续上传。 */
    private fun buildProject() {
        var promise: Promise<ProjectTaskManager.Result>? = null
        try {
            app.invokeAndWait {
                FileDocumentManager.getInstance().saveAllDocuments()
                promise = ProjectTaskManager.getInstance(project).buildAllModules()
            }
        } catch (e: Exception) {
            throw BtApiException("无法启动 IDEA Build：${e.message}")
        }

        val result = try {
            promise!!.blockingGet(30, TimeUnit.MINUTES)
        } catch (e: Exception) {
            throw BtApiException("IDEA Build 执行失败：${e.message}")
        } ?: throw BtApiException("IDEA Build 未返回结果")

        if (result.isAborted) throw BtApiException("IDEA Build 已取消")
        if (result.hasErrors()) throw BtApiException("IDEA Build 失败，请先修复编译错误")
    }

    private fun runTask(
        title: String,
        target: TargetConfig,
        successMessage: String,
        block: (log: (String) -> Unit) -> Unit,
    ) {
        if (!busy.compareAndSet(false, true)) return
        refreshTargets()

        val log: (String) -> Unit = { line -> appendLog(target, line) }
        object : Task.Backgroundable(project, title, false) {
            override fun run(indicator: ProgressIndicator) {
                var error: Exception? = null
                try {
                    block(log)
                } catch (e: Exception) {
                    error = e
                }

                val failure = error
                app.invokeLater {
                    busy.set(false)
                    if (failure == null) {
                        appendLog(target, successMessage)
                        notify(title, successMessage, NotificationType.INFORMATION)
                    } else {
                        val message = failure.message ?: failure.javaClass.simpleName
                        appendLog(target, "操作失败：$message")
                        notify("$title 失败", message, NotificationType.ERROR)
                    }
                    refreshTargets()
                    refreshStatuses()
                }
            }
        }.queue()
    }

    private fun editTarget(target: TargetConfig) {
        if (busy.get()) return
        val panel = activePanel() ?: return
        val edited = target.copy()
        val names = state.targets
            .filter { it.panelId == target.panelId && it.id != target.id }
            .map { it.name }
            .toSet()
        val dialog = TargetDialog(project, panel, edited, names)
        if (!dialog.showAndGet()) return
        dialog.apply()

        val index = state.targets.indexOfFirst { it.id == target.id }
        if (index >= 0) state.targets[index] = edited
        state.selectedTargetId = edited.id
        refreshTargets()
        refreshStatuses()
    }

    private fun deleteTarget(target: TargetConfig) {
        if (busy.get()) return
        val answer = Messages.showYesNoDialog(
            project,
            "删除部署配置「${target.name}」？",
            "删除配置",
            Messages.getWarningIcon(),
        )
        if (answer != Messages.YES) return

        state.targets.remove(target)
        logStore.clear(target.id)
        if (state.selectedTargetId == target.id) state.selectedTargetId = ""
        refreshTargets()
        refreshStatuses()
    }

    private fun warn(target: TargetConfig?, message: String) {
        if (target != null) appendLog(target, "操作失败：$message")
        notify("宝塔部署", message, NotificationType.WARNING)
    }

    private fun notify(title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("BtDeploy")
            .createNotification(title, content, type)
            .notify(project)
    }

    private fun timestamp(): String =
        SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())

    private companion object {
        const val NO_TARGET_TEXT = "选择一个部署配置查看日志"
        const val NO_LOG_TEXT = "暂无日志"
    }
}
