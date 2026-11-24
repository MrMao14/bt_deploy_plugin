package com.mrmao14.btdeploy.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.mrmao14.btdeploy.api.BtApiException
import com.mrmao14.btdeploy.api.BtClient
import com.mrmao14.btdeploy.settings.BtDeploySettings.PanelConfig
import javax.swing.JButton
import javax.swing.JComponent

class PanelDialog(
    project: Project,
    private val panel: PanelConfig,
    private val existingNames: Set<String>,
) : DialogWrapper(project) {
    private val nameField = JBTextField(panel.name)
    private val urlField = JBTextField(panel.panelUrl)
    private val apiSkField = JBPasswordField().apply { text = panel.apiSk }
    private val verifySslBox = JBCheckBox("跳过 SSL 证书校验").apply { isSelected = panel.verifySsl }
    private val testButton = JButton("测试连接")
    private val testResult = JBLabel()
    private var testing = false

    init {
        title = "面板设置"
        init()
        setSize(560, 270)
        testButton.addActionListener { testConnection() }
    }

    override fun createCenterPanel(): JComponent {
        testButton.toolTipText = "使用当前填写的信息测试面板连接"
        testResult.foreground = JBColor.GRAY
        testResult.font = testResult.font.deriveFont(11f)

        return panel {
            row("名称:") {
                cell(nameField).align(Align.FILL).resizableColumn()
            }
            row("面板地址:") {
                cell(urlField).align(Align.FILL).resizableColumn()
            }
            row("API 密钥:") {
                cell(apiSkField).align(Align.FILL).resizableColumn()
            }
            row {
                cell(verifySslBox)
            }
            row {
                cell(testButton)
                cell(testResult).align(Align.FILL).resizableColumn()
            }
            row {
                cell(JBLabel("面板地址示例：https://1.2.3.4:8888"))
            }
        }
    }

    override fun doValidate(): ValidationInfo? {
        val name = nameField.text.trim()
        if (name.isEmpty()) return ValidationInfo("名称不能为空", nameField)
        if (name in existingNames) return ValidationInfo("已存在同名面板", nameField)
        return null
    }

    fun apply() {
        panel.name = nameField.text.trim()
        panel.panelUrl = urlField.text.trim()
        panel.apiSk = String(apiSkField.password)
        panel.verifySsl = verifySslBox.isSelected
    }

    private fun testConnection() {
        if (testing) return

        val client = try {
            BtClient(urlField.text, String(apiSkField.password), verifySslBox.isSelected, 15_000)
        } catch (e: BtApiException) {
            showResult(e.message ?: "面板配置有误", error = true)
            return
        }

        testing = true
        testButton.isEnabled = false
        showResult("正在测试连接…", error = false)
        ApplicationManager.getApplication().executeOnPooledThread {
            val failure = runCatching { client.testConnection() }.exceptionOrNull()
            ApplicationManager.getApplication().invokeLater({
                testing = false
                testButton.isEnabled = true
                if (failure == null) {
                    showResult("连接成功 ✅", error = false)
                } else {
                    showResult("连接失败：${failure.message?.take(80) ?: "未知错误"}", error = true)
                }
            }, ModalityState.any())
        }
    }

    private fun showResult(text: String, error: Boolean) {
        testResult.text = text
        testResult.foreground = if (error) JBColor.RED else JBColor.GRAY
    }
}
