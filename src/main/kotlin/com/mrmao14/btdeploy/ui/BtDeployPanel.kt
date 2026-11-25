package com.mrmao14.btdeploy.ui

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.mrmao14.btdeploy.api.BtApiException
import com.mrmao14.btdeploy.api.BtClient
import com.mrmao14.btdeploy.deploy.BuildType
import com.mrmao14.btdeploy.deploy.DeployConfig
import com.mrmao14.btdeploy.deploy.Deployer
import com.mrmao14.btdeploy.deploy.ProjectBuilder
import com.mrmao14.btdeploy.deploy.RestartType
import com.mrmao14.btdeploy.deploy.StatusService
import com.mrmao14.btdeploy.deploy.TargetState
import com.mrmao14.btdeploy.deploy.TargetStatus
import com.mrmao14.btdeploy.settings.BtDeployLogStore
import com.mrmao14.btdeploy.settings.BtDeploySettings
import com.mrmao14.btdeploy.settings.BtDeploySettings.Listener
import com.mrmao14.btdeploy.settings.BtDeploySettings.PanelConfig
import com.mrmao14.btdeploy.settings.BtDeploySettings.TargetConfig
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.LayoutManager
import java.awt.Rectangle
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.Scrollable
import javax.swing.SwingConstants

class BtDeployPanel(private val project: Project) {
    private val app = ApplicationManager.getApplication()
    private val settings = app.getService(BtDeploySettings::class.java)
    private val logStore = project.getService(BtDeployLogStore::class.java)
    private val state get() = settings.state

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
        app.messageBus.connect(project).subscribe(Listener.TOPIC, Listener {
            refreshPanels()
            refreshTargets()
            refreshStatuses()
        })
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
        val cardsWrapper = ScrollablePanel(BorderLayout()).apply {
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
        SettingsDialog(project, settings, logStore, state.activePanelId).show()
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
                        buildEnabled = target.buildEnabled,
                        onSelect = { selectTarget(target.id) },
                        onBuildToggle = { target.buildEnabled = it },
                        onDeploy = { buildFirst ->
                            selectTarget(target.id)
                            deploy(target, buildFirst)
                        },
                        onStart = {
                            selectTarget(target.id)
                            serviceAction(target, "start", "启动")
                        },
                        onStop = {
                            selectTarget(target.id)
                            serviceAction(target, "stop", "停止")
                        },
                        onRestart = {
                            selectTarget(target.id)
                            serviceAction(target, "restart", "重启")
                        },
                        onRestoreBackup = {
                            selectTarget(target.id)
                            restoreBackup(target)
                        },
                        onViewLog = { viewLogs(target) },
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
        if (busy.get() || state.selectedTargetId == id) return
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
        targets.associate { it.id to TargetStatus(TargetState.UNKNOWN) }

    private fun deploy(target: TargetConfig, buildFirst: Boolean) {
        if (busy.get()) return
        val panel = activePanel() ?: return warn(target, "请先选择面板")
        if (target.localPaths.isEmpty() && !buildFirst) return warn(target, "请先给部署配置添加本地路径")
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

        val title = if (buildFirst) "打包并部署" else "部署"
        appendLog(target, "===== $title：${target.name} =====")

        runTask(title, target, if (buildFirst) "打包并部署完成 ✅" else "部署完成 ✅") { indicator, log ->
            var builtArtifact: Path? = null
            if (buildFirst) {
                log("正在构建…")
                builtArtifact = ProjectBuilder.build(project, BuildType.from(target.buildType), target.frontendDir, indicator, log)
                log("构建完成")
            }

            val config = deployConfig(target, builtArtifact)
            if (config.sources.isEmpty()) {
                throw BtApiException("没有可部署的内容：请添加本地路径，或把构建方式设为 Maven / Gradle / 前端 build")
            }
            Deployer.deploy(client, config, log)
        }
    }

    private fun deployConfig(target: TargetConfig, builtArtifact: Path? = null): DeployConfig {
        val paths = target.localPaths.map { Paths.get(it) }.toMutableList()
        if (builtArtifact != null) {
            // 配置的目录已包含产物时不再追加，否则用本次构建的 jar / 前端产物替换配置里的 jar
            val covered = paths.any { Files.isDirectory(it) && builtArtifact.startsWith(it) }
            if (!covered) {
                paths.removeAll { it.fileName.toString().endsWith(".jar", true) }
                paths.add(builtArtifact)
            }
        }
        return DeployConfig(
            sources = paths,
            remoteDir = target.remoteDir,
            keepRoot = target.keepRoot,
            restart = RestartType.from(target.restartType),
            serviceName = target.serviceName,
            projectName = target.javaProjectName,
        )
    }

    private fun serviceAction(target: TargetConfig, operation: String, label: String) {
        if (busy.get()) return
        val panel = activePanel() ?: return warn(target, "请先选择面板")
        val java = RestartType.from(target.restartType) == RestartType.JAVA_RESTART
        val name = if (java) {
            target.javaProjectName.trim()
        } else {
            target.serviceName.trim().ifBlank { "webserver" }
        }
        if (name.isBlank()) return warn(target, "请先配置 Java 项目名称")

        val client = try {
            BtClient(panel.panelUrl, panel.apiSk, panel.verifySsl)
        } catch (e: BtApiException) {
            warn(target, e.message ?: "面板配置有误")
            return
        }

        state.selectedTargetId = target.id
        refreshTargets()
        prepareLogFor(target)
        val title = "$label ${target.name}"
        appendLog(target, "===== $title =====")
        runTask(title, target, "${label}完成 ✅") { _, log ->
            if (java) {
                client.javaProjectAction(name, operation)
                log("      已${label}Java 项目 $name")
            } else {
                client.serviceAdmin(name, operation)
                log("      已${label}服务 $name")
            }
        }
    }

    private fun restoreBackup(target: TargetConfig) {
        if (!busy.compareAndSet(false, true)) return
        val panel = activePanel()
        val remoteDir = target.remoteDir.trim()
        if (panel == null || remoteDir.isEmpty()) {
            busy.set(false)
            return warn(target, if (panel == null) "请先选择面板" else "请先填写远程目录")
        }

        val client = try {
            BtClient(panel.panelUrl, panel.apiSk, panel.verifySsl)
        } catch (e: BtApiException) {
            busy.set(false)
            return warn(target, e.message ?: "面板配置有误")
        }

        state.selectedTargetId = target.id
        refreshTargets()
        app.executeOnPooledThread {
            val result = runCatching { Deployer.listBackups(client, remoteDir) }
            app.invokeLater {
                busy.set(false)
                refreshTargets()
                val failure = result.exceptionOrNull()
                if (failure != null) {
                    warn(target, "读取备份失败：${failure.message ?: failure.javaClass.simpleName}")
                    return@invokeLater
                }

                val backups = result.getOrThrow()
                if (backups.isEmpty()) {
                    warn(target, "该配置还没有备份；部署成功一次后会保存在远程 bak/ 目录")
                    return@invokeLater
                }

                val labels = backups.map { "${it.name}  ${formatSize(it.size)}  ${formatBackupTime(it.mtime)}" }
                val index = Messages.showChooseDialog(
                    project,
                    "选择要还原的备份：",
                    "还原备份",
                    Messages.getQuestionIcon(),
                    labels.toTypedArray(),
                    labels.first(),
                )
                if (index < 0) return@invokeLater

                val backup = backups[index]
                val answer = Messages.showYesNoDialog(
                    project,
                    "用「${backup.name}」覆盖 ${target.remoteDir} 里的现有文件？",
                    "确认还原",
                    Messages.getWarningIcon(),
                )
                if (answer != Messages.YES) return@invokeLater

                prepareLogFor(target)
                appendLog(target, "===== 还原备份：${backup.name} =====")
                val config = deployConfig(target)
                runTask("还原备份", target, "还原完成 ✅") { _, log ->
                    Deployer.restoreBackup(client, config, backup.name, log)
                }
            }
        }
    }

    private fun prepareLogFor(target: TargetConfig) {
        shownLogTargetId = target.id
        logArea.text = logStore.text(target.id)
        logArea.caretPosition = logArea.document.length
    }

    private fun runTask(
        title: String,
        target: TargetConfig,
        successMessage: String,
        block: (indicator: ProgressIndicator, log: (String) -> Unit) -> Unit,
    ) {
        if (!busy.compareAndSet(false, true)) return
        refreshTargets()

        val log: (String) -> Unit = { line -> appendLog(target, line) }
        object : Task.Backgroundable(project, title, false) {
            override fun run(indicator: ProgressIndicator) {
                var error: Exception? = null
                try {
                    block(indicator, log)
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

    private fun viewLogs(target: TargetConfig) {
        if (target.remoteDir.isBlank()) {
            notify("宝塔部署", "「${target.name}」未配置远程目录", NotificationType.WARNING)
            return
        }
        val panel = activePanel() ?: return notify("宝塔部署", "请先选择面板", NotificationType.WARNING)
        val client = try {
            BtClient(panel.panelUrl, panel.apiSk, panel.verifySsl, 30_000)
        } catch (e: BtApiException) {
            notify("宝塔部署", e.message ?: "面板配置有误", NotificationType.ERROR)
            return
        }
        TargetLogDialog(project, target, client).show()
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

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(Locale.ROOT, bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> "%.1f KB".format(Locale.ROOT, bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun formatBackupTime(seconds: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(Date(seconds * 1000))

    /** 宽度跟随工具窗口，高度按卡片实际高度，避免卡片被拉高。 */
    private class ScrollablePanel(layout: LayoutManager) : JPanel(layout), Scrollable {
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = 16

        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
            visibleRect.height

        override fun getScrollableTracksViewportWidth(): Boolean = true

        override fun getScrollableTracksViewportHeight(): Boolean = false
    }

    private companion object {
        const val NO_TARGET_TEXT = "选择一个部署配置查看日志"
        const val NO_LOG_TEXT = "暂无日志"
    }
}
