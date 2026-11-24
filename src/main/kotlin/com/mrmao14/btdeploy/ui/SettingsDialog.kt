package com.mrmao14.btdeploy.ui

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.mrmao14.btdeploy.settings.BtDeployLogStore
import com.mrmao14.btdeploy.settings.BtDeploySettings
import com.mrmao14.btdeploy.settings.BtDeploySettings.PanelConfig
import com.mrmao14.btdeploy.settings.BtDeploySettings.TargetConfig
import com.mrmao14.btdeploy.settings.DeployConfigJson
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.Paths
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.ListSelectionModel

/** 右上角「设置」：管理面板连接和部署配置，修改立即生效。 */
class SettingsDialog(
    private val project: Project,
    private val settings: BtDeploySettings,
    private val logStore: BtDeployLogStore,
    private val initialPanelId: String,
) : DialogWrapper(project) {
    private val state get() = settings.state

    private val panelModel = DefaultListModel<PanelConfig>()
    private val panelList = JBList(panelModel)
    private val addPanelButton = JButton("新增面板")
    private val editPanelButton = JButton("编辑")
    private val deletePanelButton = JButton("删除")

    private val configPanelCombo = JComboBox<PanelConfig>()
    private val targetModel = DefaultListModel<TargetConfig>()
    private val targetList = JBList(targetModel)
    private val addTargetButton = JButton("新增配置")
    private val editTargetButton = JButton("编辑")
    private val deleteTargetButton = JButton("删除")
    private val importConfigButton = JButton("导入配置")
    private val exportConfigButton = JButton("导出配置")

    private val tabs = JTabbedPane()
    private var refreshingCombo = false

    private val closeAction = object : AbstractAction("关闭") {
        override fun actionPerformed(e: ActionEvent) {
            close(DialogWrapper.OK_EXIT_CODE)
        }
    }

    init {
        title = "宝塔部署设置"
        init()
        setSize(800, 580)

        panelList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        targetList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        panelList.emptyText.text = "暂无面板"
        targetList.emptyText.text = "该面板还没有部署配置"
        panelList.toolTipText = "双击编辑面板"
        targetList.toolTipText = "双击编辑部署配置"
        panelList.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
            ): Component {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                if (value is PanelConfig) text = "${value.name}   ${value.panelUrl}"
                return this
            }
        }
        targetList.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
            ): Component {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                if (value is TargetConfig) text = "${value.name}   ${value.remoteDir}"
                return this
            }
        }

        bindEvents()
        refreshPanelList(initialPanelId)
        refreshConfigPanelCombo(initialPanelId)
        refreshTargetList()
    }

    override fun createCenterPanel(): JComponent {
        tabs.addTab("面板", buildPanelTab())
        tabs.addTab("部署配置", buildTargetTab())
        return tabs
    }

    override fun createActions(): Array<Action> = arrayOf(closeAction)

    private fun buildPanelTab(): JComponent {
        addPanelButton.toolTipText = "新增一个服务器面板"
        editPanelButton.toolTipText = "编辑面板并测试连接"
        deletePanelButton.toolTipText = "删除面板及它的部署配置"

        return JPanel(BorderLayout(0, 6)).apply {
            border = JBUI.Borders.empty(8)
            add(JBLabel("面板保存服务器连接信息；编辑面板时可以直接测试连接。"), BorderLayout.NORTH)
            add(JBScrollPane(panelList), BorderLayout.CENTER)
            add(buttonRow(addPanelButton, editPanelButton, deletePanelButton), BorderLayout.SOUTH)
        }
    }

    private fun buildTargetTab(): JComponent {
        addTargetButton.toolTipText = "在当前面板下新增部署配置"
        editTargetButton.toolTipText = "编辑选中的部署配置"
        deleteTargetButton.toolTipText = "删除选中的部署配置"
        importConfigButton.toolTipText = "导入历史版本 JSON 配置，同名配置覆盖，其余追加"
        exportConfigButton.toolTipText = "导出当前面板的配置，文件不包含 API 密钥"

        val panelRow = JPanel(BorderLayout(6, 0)).apply {
            add(JBLabel("面板:"), BorderLayout.WEST)
            add(configPanelCombo, BorderLayout.CENTER)
        }
        val actions = JPanel(BorderLayout(0, 4)).apply {
            add(buttonRow(addTargetButton, editTargetButton, deleteTargetButton), BorderLayout.NORTH)
            add(buttonRow(importConfigButton, exportConfigButton), BorderLayout.SOUTH)
        }
        return JPanel(BorderLayout(0, 6)).apply {
            border = JBUI.Borders.empty(8)
            add(panelRow, BorderLayout.NORTH)
            add(JBScrollPane(targetList), BorderLayout.CENTER)
            add(actions, BorderLayout.SOUTH)
        }
    }

    private fun buttonRow(vararg buttons: JButton): JPanel =
        JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { buttons.forEach(::add) }

    private fun bindEvents() {
        panelList.addListSelectionListener { updatePanelButtons() }
        targetList.addListSelectionListener { updateTargetButtons() }
        configPanelCombo.addActionListener {
            if (!refreshingCombo) refreshTargetList()
        }

        panelList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) panelList.selectedValue?.let(::editPanel)
            }
        })
        targetList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) targetList.selectedValue?.let(::editTarget)
            }
        })

        addPanelButton.addActionListener { addPanel() }
        editPanelButton.addActionListener { panelList.selectedValue?.let(::editPanel) }
        deletePanelButton.addActionListener { panelList.selectedValue?.let(::deletePanel) }
        addTargetButton.addActionListener { addTarget() }
        editTargetButton.addActionListener { targetList.selectedValue?.let(::editTarget) }
        deleteTargetButton.addActionListener { targetList.selectedValue?.let(::deleteTarget) }
        importConfigButton.addActionListener { importConfig() }
        exportConfigButton.addActionListener { exportConfig() }
    }

    private fun refreshPanelList(selectId: String? = null) {
        val selectedId = selectId
            ?: panelList.selectedValue?.id
            ?: state.panels.firstOrNull()?.id
        panelModel.clear()
        state.panels.forEach(panelModel::addElement)
        val index = state.panels.indexOfFirst { it.id == selectedId }
        panelList.selectedIndex = if (index >= 0) index else if (panelModel.size() > 0) 0 else -1
        updatePanelButtons()
    }

    private fun refreshConfigPanelCombo(selectId: String? = null) {
        refreshingCombo = true
        val selectedId = selectId
            ?: (configPanelCombo.selectedItem as? PanelConfig)?.id
            ?: state.activePanelId
        configPanelCombo.removeAllItems()
        state.panels.forEach(configPanelCombo::addItem)
        configPanelCombo.selectedItem =
            state.panels.firstOrNull { it.id == selectedId } ?: state.panels.firstOrNull()
        refreshingCombo = false
    }

    private fun refreshTargetList(selectId: String? = null) {
        val selectedId = selectId ?: targetList.selectedValue?.id
        val panel = selectedConfigPanel()
        targetModel.clear()
        if (panel != null) {
            state.targets.filter { it.panelId == panel.id }.forEach(targetModel::addElement)
        }
        val index = (0 until targetModel.size()).firstOrNull { targetModel.get(it).id == selectedId }
        targetList.selectedIndex = index ?: if (targetModel.size() > 0) 0 else -1
        updateTargetButtons()
    }

    private fun updatePanelButtons() {
        val selected = panelList.selectedValue
        editPanelButton.isEnabled = selected != null
        deletePanelButton.isEnabled = selected != null && state.panels.size > 1
    }

    private fun updateTargetButtons() {
        val selected = targetList.selectedValue
        val panel = selectedConfigPanel()
        addTargetButton.isEnabled = panel != null
        editTargetButton.isEnabled = selected != null
        deleteTargetButton.isEnabled = selected != null
        importConfigButton.isEnabled = panel != null
        exportConfigButton.isEnabled = panel != null && targetModel.size() > 0
    }

    private fun selectedConfigPanel(): PanelConfig? = configPanelCombo.selectedItem as? PanelConfig

    private fun addPanel() {
        val panel = BtDeploySettings.createPanel(settings.nextPanelName())
        val names = state.panels.map { it.name }.toSet()
        val dialog = PanelDialog(project, panel, names)
        if (!dialog.showAndGet()) return
        dialog.apply()

        state.panels.add(panel)
        settings.notifyChanged()
        refreshPanelList(panel.id)
        refreshConfigPanelCombo(panel.id)
        refreshTargetList()
    }

    private fun editPanel(panel: PanelConfig) {
        val edited = panel.copy()
        val names = state.panels.filter { it.id != panel.id }.map { it.name }.toSet()
        val dialog = PanelDialog(project, edited, names)
        if (!dialog.showAndGet()) return
        dialog.apply()

        val index = state.panels.indexOfFirst { it.id == panel.id }
        if (index >= 0) state.panels[index] = edited
        settings.notifyChanged()
        refreshPanelList(panel.id)
        refreshConfigPanelCombo(panel.id)
        refreshTargetList()
    }

    private fun deletePanel(panel: PanelConfig) {
        if (state.panels.size <= 1) {
            Messages.showWarningDialog(project, "至少保留一个面板", "删除面板")
            return
        }
        val count = state.targets.count { it.panelId == panel.id }
        val suffix = if (count > 0) "及 $count 个部署配置" else ""
        val answer = Messages.showYesNoDialog(
            project,
            "删除面板「${panel.name}」$suffix？",
            "删除面板",
            Messages.getWarningIcon(),
        )
        if (answer != Messages.YES) return

        state.targets.filter { it.panelId == panel.id }.forEach { logStore.clear(it.id) }
        state.targets.removeAll { it.panelId == panel.id }
        state.panels.remove(panel)
        if (state.activePanelId == panel.id) {
            state.activePanelId = state.panels.first().id
        }
        settings.notifyChanged()
        refreshPanelList()
        refreshConfigPanelCombo()
        refreshTargetList()
    }

    private fun addTarget() {
        val panel = selectedConfigPanel() ?: return
        val target = BtDeploySettings.createTarget(panel.id, settings.nextTargetName(panel.id))
        val names = state.targets.filter { it.panelId == panel.id }.map { it.name }.toSet()
        val dialog = TargetDialog(project, panel, target, names)
        if (!dialog.showAndGet()) return
        dialog.apply()

        state.targets.add(target)
        settings.notifyChanged()
        refreshTargetList(target.id)
    }

    private fun editTarget(target: TargetConfig) {
        val panel = state.panels.firstOrNull { it.id == target.panelId } ?: return
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
        settings.notifyChanged()
        refreshTargetList(target.id)
    }

    private fun deleteTarget(target: TargetConfig) {
        val answer = Messages.showYesNoDialog(
            project,
            "删除部署配置「${target.name}」？",
            "删除配置",
            Messages.getWarningIcon(),
        )
        if (answer != Messages.YES) return

        state.targets.remove(target)
        logStore.clear(target.id)
        settings.notifyChanged()
        refreshTargetList()
    }

    private fun importConfig() {
        val panel = selectedConfigPanel() ?: return
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("json")
            .withTitle("导入部署配置")
            .withDescription("选择历史版本导出的 JSON 配置文件")
        val file = FileChooser.chooseFile(descriptor, project, null) ?: return

        val result = try {
            DeployConfigJson.importTargets(
                settings,
                panel,
                Files.readString(Paths.get(file.path), Charsets.UTF_8),
            )
        } catch (e: Exception) {
            Messages.showErrorDialog(project, "读取失败：${e.message ?: e.javaClass.simpleName}", "导入配置")
            return
        }

        if (result == null) {
            Messages.showWarningDialog(project, "文件里没有 targets 数组", "导入配置")
            return
        }
        if (result.added + result.replaced == 0) {
            Messages.showWarningDialog(project, "文件里没有可导入的部署配置", "导入配置")
            return
        }

        settings.notifyChanged()
        refreshPanelList(panel.id)
        refreshConfigPanelCombo(panel.id)
        refreshTargetList(result.selectedTargetId)
        val keyNote = if (result.apiKeyImported) "API 密钥已随文件带入" else "API 密钥未导入，请确认面板配置"
        Messages.showInfoMessage(
            project,
            "已导入：新增 ${result.added} 个，覆盖 ${result.replaced} 个。$keyNote。",
            "导入配置",
        )
    }

    private fun exportConfig() {
        val panel = selectedConfigPanel() ?: return
        val targets = state.targets.filter { it.panelId == panel.id }
        if (targets.isEmpty()) {
            Messages.showInfoMessage(project, "还没有可导出的部署配置", "导出配置")
            return
        }

        val descriptor = FileSaverDescriptor("导出部署配置", "选择 JSON 配置保存位置", "json")
        val file = FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)
            .save(project.basePath?.let { Paths.get(it) }, "bt-deploy-targets.json")
            ?: return

        try {
            Files.writeString(file.file.toPath(), DeployConfigJson.export(panel, targets), Charsets.UTF_8)
        } catch (e: Exception) {
            Messages.showErrorDialog(project, "导出失败：${e.message ?: e.javaClass.simpleName}", "导出配置")
            return
        }
        Messages.showInfoMessage(
            project,
            "已导出 ${targets.size} 个部署配置到 ${file.file.absolutePath}（不含 API 密钥）",
            "导出配置",
        )
    }
}
