package com.mrmao14.btdeploy.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.panel
import com.mrmao14.btdeploy.api.BtApiException
import com.mrmao14.btdeploy.api.BtClient
import com.mrmao14.btdeploy.deploy.BuildType
import com.mrmao14.btdeploy.deploy.RestartType
import com.mrmao14.btdeploy.settings.BtDeploySettings.PanelConfig
import com.mrmao14.btdeploy.settings.BtDeploySettings.TargetConfig
import java.awt.Dimension
import java.awt.GridLayout
import java.nio.file.Paths
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel

class TargetDialog(
    private val project: Project,
    private val panel: PanelConfig,
    private val target: TargetConfig,
    private val existingNames: Set<String>,
) : DialogWrapper(project) {
    private val nameField = JBTextField(target.name)
    private val pathModel = DefaultListModel<String>()
    private val pathList = JBList(pathModel)
    private val remoteDirCombo = JComboBox<String>().apply { isEditable = true }
    private val javaProjectCombo = JComboBox<String>().apply { isEditable = true }
    private val serviceNameCombo = JComboBox<String>().apply { isEditable = true }
    private val refreshButton = JButton("刷新")
    private val dataHint = JBLabel()
    private val keepRootBox = JBCheckBox("保留目录名").apply { isSelected = target.keepRoot }
    private val buildTypeCombo = JComboBox<BuildType>()
    private val deployTypeCombo = JComboBox(DeployType.entries.toTypedArray())
    private val frontendDirField = JBTextField(target.frontendDir)
    private val frontendDirButton = JButton("选择")
    private val restartCombo = JComboBox(RestartType.entries.toTypedArray())
    private val addDirButton = JButton("添加目录")
    private val addFileButton = JButton("添加文件")
    private val removeButton = JButton("移除")

    private lateinit var buildTypeRow: Row
    private lateinit var frontendDirRow: Row
    private lateinit var javaProjectRow: Row
    private lateinit var serviceNameRow: Row

    private val javaProjectPaths = mutableMapOf<String, String>()
    private var updatingOptions = false
    private var loadingOptions = false

    init {
        target.localPaths.forEach(pathModel::addElement)
        val deployType = initialDeployType(target)
        deployTypeCombo.selectedItem = deployType
        buildTypeOptions(deployType).forEach(buildTypeCombo::addItem)
        buildTypeCombo.selectedItem =
            BuildType.from(target.buildType).takeIf { it in buildTypeOptions(deployType) } ?: BuildType.AUTO
        restartCombo.selectedItem = RestartType.from(target.restartType)

        COMMON_SERVICES.forEach(serviceNameCombo::addItem)
        setComboValue(serviceNameCombo, target.serviceName)
        setComboValue(javaProjectCombo, target.javaProjectName)
        setComboValue(remoteDirCombo, target.remoteDir)

        title = "部署配置"
        init()
        setSize(700, 560)

        restartCombo.addActionListener { updateRestartFields() }
        deployTypeCombo.addActionListener { updateDeployTypeFields() }
        addDirButton.addActionListener { choosePath(directory = true) }
        addFileButton.addActionListener { choosePath(directory = false) }
        removeButton.addActionListener { removeSelectedPaths() }
        refreshButton.addActionListener { loadPanelOptions() }
        frontendDirButton.addActionListener { chooseFrontendDir() }
        javaProjectCombo.addActionListener {
            if (updatingOptions) return@addActionListener
            val path = javaProjectPaths[javaProjectCombo.value()]
            if (!path.isNullOrBlank()) setComboValue(remoteDirCombo, path)
        }

        updateDeployTypeFields()
        loadPanelOptions()
    }

    override fun createCenterPanel(): JComponent {
        pathList.emptyText.text = "尚未添加本地路径"
        pathList.visibleRowCount = 4
        dataHint.foreground = JBColor.GRAY
        dataHint.font = dataHint.font.deriveFont(11f)
        remoteDirCombo.toolTipText = "面板上的网站目录或 Java 项目目录，也可以直接输入"
        javaProjectCombo.toolTipText = "选择 Java 项目后自动填入项目目录"
        serviceNameCombo.toolTipText = "常用系统服务，也可以直接输入"
        refreshButton.toolTipText = "重新从面板读取网站目录和 Java 项目"
        deployTypeCombo.toolTipText = "Java 项目按构建方式打包 jar；前端执行 package.json 的 build；其他用于系统服务类部署"
        buildTypeCombo.toolTipText = "自动识别：有 pom.xml 用 Maven，有 build.gradle 用 Gradle；IDEA Build 只编译不打 jar"
        frontendDirField.toolTipText = "前端项目目录，相对 IDEA 项目根目录；留空表示项目根目录"
        frontendDirButton.toolTipText = "选择前端项目目录"

        val pathButtons = JPanel(GridLayout(3, 1, 0, 4)).apply {
            add(addDirButton)
            add(addFileButton)
            add(removeButton)
        }

        return panel {
            row("部署类型:") {
                cell(deployTypeCombo)
            }
            row("名称:") {
                cell(nameField).align(Align.FILL).resizableColumn()
            }
            javaProjectRow = row("项目名称:") {
                cell(javaProjectCombo).align(Align.FILL).resizableColumn()
            }
            row("部署后动作:") {
                cell(restartCombo)
            }
            serviceNameRow = row("服务名:") {
                cell(serviceNameCombo).align(Align.FILL).resizableColumn()
            }
            buildTypeRow = row("构建方式:") {
                cell(buildTypeCombo)
                cell(JBLabel("构建成功后再部署产物").apply {
                    foreground = JBColor.GRAY
                    font = font.deriveFont(11f)
                })
            }
            frontendDirRow = row("前端目录:") {
                cell(frontendDirField).align(Align.FILL).resizableColumn()
                cell(frontendDirButton)
                cell(JBLabel("相对项目根目录，留空为根目录").apply {
                    foreground = JBColor.GRAY
                    font = font.deriveFont(11f)
                })
            }
            row("本地路径:") {
                cell(JBScrollPane(pathList).apply { preferredSize = Dimension(0, 96) })
                    .align(Align.FILL).resizableColumn()
                cell(pathButtons)
            }
            row("远程目录:") {
                cell(remoteDirCombo).align(Align.FILL).resizableColumn()
                cell(refreshButton)
            }
            row {
                cell(dataHint)
            }
            row {
                cell(keepRootBox)
            }
        }
    }

    override fun doValidate(): ValidationInfo? {
        val name = nameField.text.trim()
        if (name.isEmpty()) return ValidationInfo("名称不能为空", nameField)
        if (name in existingNames) return ValidationInfo("已存在同名配置", nameField)
        return null
    }

    fun apply() {
        target.name = nameField.text.trim()
        target.localPaths = (0 until pathModel.size()).mapTo(mutableListOf()) { pathModel.get(it) }
        val type = selectedDeployType()
        target.deployType = type.name
        target.buildType = if (type == DeployType.FRONTEND) {
            BuildType.FRONTEND.name
        } else {
            (buildTypeCombo.selectedItem as? BuildType ?: BuildType.AUTO).name
        }
        target.frontendDir = frontendDirField.text.trim()
        target.remoteDir = remoteDirCombo.value()
        target.keepRoot = keepRootBox.isSelected
        target.restartType = (restartCombo.selectedItem as RestartType).name
        target.serviceName = serviceNameCombo.value()
        target.javaProjectName = javaProjectCombo.value()
    }

    private fun choosePath(directory: Boolean) {
        val descriptor = if (directory) {
            FileChooserDescriptorFactory.singleDir()
        } else {
            FileChooserDescriptorFactory.singleFile()
        }
        val file = FileChooser.chooseFile(
            descriptor.withTitle(if (directory) "选择要部署的目录" else "选择要部署的文件"),
            project,
            null,
        ) ?: return

        val path = Paths.get(file.path).toAbsolutePath().normalize().toString()
        if ((0 until pathModel.size()).none { pathModel.get(it) == path }) {
            pathModel.addElement(path)
        }
    }

    private fun chooseFrontendDir() {
        val file = FileChooser.chooseFile(
            FileChooserDescriptorFactory.singleDir().withTitle("选择前端项目目录"),
            project,
            null,
        ) ?: return
        val path = Paths.get(file.path).toAbsolutePath().normalize()
        val base = project.basePath?.let { Paths.get(it).toAbsolutePath().normalize() }
        frontendDirField.text = runCatching { base?.relativize(path)?.toString() }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: path.toString()
    }

    private fun removeSelectedPaths() {
        pathList.selectedValuesList.forEach(pathModel::removeElement)
    }

    private fun updateDeployTypeFields() {
        val type = selectedDeployType()
        javaProjectRow.visible(type == DeployType.JAVA)
        buildTypeRow.visible(type != DeployType.FRONTEND)
        frontendDirRow.visible(type == DeployType.FRONTEND)
        serviceNameRow.visible(type == DeployType.OTHER)

        if (type != DeployType.FRONTEND) {
            val options = buildTypeOptions(type)
            val current = buildTypeCombo.selectedItem as? BuildType
            val items = (0 until buildTypeCombo.itemCount).map { buildTypeCombo.getItemAt(it) }
            if (items != options) {
                buildTypeCombo.removeAllItems()
                options.forEach(buildTypeCombo::addItem)
                buildTypeCombo.selectedItem = current?.takeIf { it in options } ?: BuildType.AUTO
            }
        }

        val selected = restartCombo.selectedItem as? RestartType ?: RestartType.NONE
        val options = restartOptions(type)
        restartCombo.removeAllItems()
        options.forEach(restartCombo::addItem)
        restartCombo.selectedItem = if (selected in options) selected else RestartType.NONE
        updateRestartFields()
    }

    private fun selectedDeployType(): DeployType =
        deployTypeCombo.selectedItem as? DeployType ?: DeployType.JAVA

    private fun initialDeployType(target: TargetConfig): DeployType = when {
        target.deployType.isNotBlank() -> DeployType.from(target.deployType)
        BuildType.from(target.buildType) == BuildType.FRONTEND -> DeployType.FRONTEND
        else -> DeployType.JAVA
    }

    private fun buildTypeOptions(type: DeployType): List<BuildType> = when (type) {
        DeployType.JAVA -> listOf(BuildType.AUTO, BuildType.IDEA, BuildType.MAVEN, BuildType.GRADLE)
        DeployType.OTHER -> listOf(BuildType.AUTO, BuildType.IDEA)
        DeployType.FRONTEND -> emptyList()
    }

    private fun restartOptions(type: DeployType): List<RestartType> = when (type) {
        DeployType.JAVA -> RestartType.entries.filter { it != RestartType.SERVICE_RESTART }
        DeployType.FRONTEND -> listOf(RestartType.NONE, RestartType.WEBSERVER_RELOAD)
        DeployType.OTHER -> RestartType.entries.filter { it != RestartType.JAVA_RESTART }
    }

    private fun updateRestartFields() {
        val type = restartCombo.selectedItem as? RestartType ?: RestartType.NONE
        serviceNameCombo.isEnabled = type == RestartType.SERVICE_RESTART
    }

    /** 从面板读取网站目录和 Java 项目，异步填充下拉框；失败时仍可手动输入。 */
    private fun loadPanelOptions() {
        if (loadingOptions) return

        val url = panel.panelUrl.trim()
        val apiSk = panel.apiSk.trim()
        if (url.isEmpty() || apiSk.isEmpty()) {
            dataHint.text = "面板地址或 API 密钥为空，可直接输入目录"
            return
        }

        val client = try {
            BtClient(url, apiSk, panel.verifySsl, 20_000)
        } catch (e: BtApiException) {
            dataHint.text = e.message ?: "面板配置有误"
            return
        }

        loadingOptions = true
        refreshButton.isEnabled = false
        dataHint.text = "正在读取面板数据…"
        ApplicationManager.getApplication().executeOnPooledThread {
            var error: Exception? = null
            val sites = try {
                client.listSites()
            } catch (e: Exception) {
                error = e
                emptyList()
            }
            val projects = try {
                client.listJavaProjects()
            } catch (e: Exception) {
                if (error == null) error = e
                emptyList()
            }

            ApplicationManager.getApplication().invokeLater({
                val failure = error
                loadingOptions = false
                refreshButton.isEnabled = true

                updatingOptions = true
                val remote = remoteDirCombo.value()
                val javaName = javaProjectCombo.value()
                val service = serviceNameCombo.value()

                sites.forEach { addItemIfMissing(remoteDirCombo, it.path) }
                projects.forEach {
                    addItemIfMissing(javaProjectCombo, it.name)
                    if (it.path.isNotBlank()) {
                        javaProjectPaths[it.name] = it.path
                        addItemIfMissing(remoteDirCombo, it.path)
                    }
                }

                setComboValue(remoteDirCombo, remote.ifBlank { target.remoteDir })
                setComboValue(javaProjectCombo, javaName.ifBlank { target.javaProjectName })
                setComboValue(serviceNameCombo, service.ifBlank { target.serviceName })
                updatingOptions = false

                dataHint.text = when {
                    failure != null && sites.isEmpty() && projects.isEmpty() ->
                        "读取面板数据失败：${failure.message?.take(40) ?: "未知错误"}，可直接输入"

                    sites.isEmpty() && projects.isEmpty() ->
                        "未读取到网站或 Java 项目，可直接输入"

                    else ->
                        "已读取 ${sites.size} 个网站目录、${projects.size} 个 Java 项目"
                }
            }, ModalityState.any())
        }
    }

    private fun addItemIfMissing(combo: JComboBox<String>, value: String) {
        if (value.isBlank()) return
        for (index in 0 until combo.itemCount) {
            if (combo.getItemAt(index) == value) return
        }
        combo.addItem(value)
    }

    private fun setComboValue(combo: JComboBox<String>, value: String) {
        if (value.isNotBlank()) addItemIfMissing(combo, value)
        combo.selectedItem = value.ifBlank { null }
        combo.editor.item = value
    }

    private fun JComboBox<String>.value(): String {
        val editorValue = editor.item as? String
        return (editorValue ?: selectedItem as? String).orEmpty().trim()
    }

    private companion object {
        val COMMON_SERVICES = listOf(
            "nginx", "apache", "httpd", "mysqld", "mysql", "mariadb",
            "redis", "memcached", "tomcat", "php-fpm", "pure-ftpd",
        )
    }
}

/** 部署类型只影响界面：Java 显示项目名称和构建方式，前端固定走 package.json 的 build，其他用于系统服务。 */
private enum class DeployType(val label: String) {
    JAVA("Java"),
    FRONTEND("前端"),
    OTHER("其他");

    override fun toString() = label

    companion object {
        fun from(value: String?): DeployType = entries.firstOrNull { it.name == value } ?: JAVA
    }
}
