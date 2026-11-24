package com.mrmao14.btdeploy.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.mrmao14.btdeploy.deploy.RestartType
import com.mrmao14.btdeploy.deploy.TargetState
import com.mrmao14.btdeploy.deploy.TargetStatus
import com.mrmao14.btdeploy.settings.BtDeploySettings.TargetConfig
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Insets
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractButton
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu

class TargetCard(
    private val target: TargetConfig,
    status: TargetStatus?,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onDeploy: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onRestoreBackup: () -> Unit,
    onViewLog: () -> Unit,
) : JPanel(BorderLayout(0, 6)) {
    private val clickAdapter = object : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent) {
            onSelect()
        }
    }

    init {
        isOpaque = true
        background = if (selected) {
            UIUtil.getListSelectionBackground(false)
        } else {
            UIUtil.getPanelBackground()
        }
        border = BorderFactory.createCompoundBorder(
            JBUI.Borders.customLine(
                if (selected) JBColor.namedColor("Component.focusColor", JBColor(0x3B82F6, 0x5B9CF6))
                else JBColor.namedColor("Component.borderColor", JBColor(0xD1D1D1, 0x4E5157)),
                if (selected) 2 else 1,
            ),
            JBUI.Borders.empty(if (selected) 8 else 9),
        )
        preferredSize = Dimension(380, 128)
        minimumSize = Dimension(220, 128)
        maximumSize = Dimension(Int.MAX_VALUE, 128)
        alignmentX = LEFT_ALIGNMENT

        val nameLabel = JBLabel(target.name.ifBlank { "未命名配置" }).apply {
            font = font.deriveFont(Font.BOLD, 13f)
        }
        val state = status?.state
        val statusPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            isOpaque = false
            add(JBLabel("●").apply {
                foreground = statusColor(state)
                font = font.deriveFont(14f)
            })
            add(JBLabel(state?.label ?: "查询中…").apply {
                font = font.deriveFont(13f)
            })
        }
        val top = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(nameLabel, BorderLayout.WEST)
            add(statusPanel, BorderLayout.EAST)
        }

        val remoteLabel = JBLabel(elide(target.remoteDir.ifBlank { "未设置远程目录" })).apply {
            foreground = JBColor.GRAY
            alignmentX = LEFT_ALIGNMENT
        }
        val infoLabel = JBLabel(infoText()).apply {
            foreground = JBColor.GRAY
            font = font.deriveFont(11f)
            alignmentX = LEFT_ALIGNMENT
        }
        val middle = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(remoteLabel)
            add(Box.createVerticalStrut(2))
            add(infoLabel)
        }

        val compact = Insets(3, 8, 3, 8)
        val buildCheckBox = JBCheckBox("打包").apply {
            isSelected = true
            isEnabled = enabled
            toolTipText = "勾选后先执行 IDEA Build，成功后再部署"
        }
        val deployButton = JButton("部署").apply {
            isEnabled = enabled
            margin = compact
            toolTipText = "根据左侧勾选状态执行打包或直接上传"
            addActionListener { onDeploy(buildCheckBox.isSelected) }
        }
        val viewLogButton = JButton("查看日志").apply {
            margin = compact
            addActionListener { onViewLog() }
        }
        val operationButton = JButton("操作 ▾").apply {
            isEnabled = enabled
            margin = compact
            toolTipText = "启动、停止、重启或还原备份"
            addActionListener {
                val menu = JPopupMenu().apply {
                    add(menuItem("启动", onStart))
                    add(menuItem("停止", onStop))
                    add(menuItem("重启", onRestart))
                    addSeparator()
                    add(menuItem("还原备份", onRestoreBackup))
                }
                menu.show(this, 0, height)
            }
        }
        val leftActions = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            add(buildCheckBox)
            add(deployButton)
        }
        val rightActions = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            isOpaque = false
            add(viewLogButton)
            add(operationButton)
        }
        val actions = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(leftActions, BorderLayout.WEST)
            add(rightActions, BorderLayout.EAST)
        }

        add(top, BorderLayout.NORTH)
        add(middle, BorderLayout.CENTER)
        add(actions, BorderLayout.SOUTH)

        toolTipText = buildString {
            append(escape(target.remoteDir.ifBlank { "未设置远程目录" }))
            append("<br>状态：")
            append(state?.label ?: "查询中…")
            if (target.localPaths.isNotEmpty()) {
                append("<br>")
                append(target.localPaths.joinToString("<br>") { escape(it) })
            }
        }.let { "<html>$it</html>" }

        install(this)
    }

    private fun install(component: Component) {
        if (component is AbstractButton) return
        component.addMouseListener(clickAdapter)
        component.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        if (component is Container) {
            component.components.forEach(::install)
        }
    }

    private fun infoText(): String {
        val type = RestartType.from(target.restartType)
        val extra = when (type) {
            RestartType.SERVICE_RESTART -> target.serviceName.trim()
            RestartType.JAVA_RESTART -> target.javaProjectName.trim()
            else -> ""
        }
        val base = "${target.localPaths.size} 个路径 · ${type.label}"
        return elide(if (extra.isBlank()) base else "$base · $extra")
    }

    private fun statusColor(state: TargetState?): Color = when (state) {
        TargetState.RUNNING -> JBColor(0x2EA043, 0x3FB950)
        TargetState.STOPPED -> JBColor(0xCF222E, 0xF85149)
        TargetState.UNKNOWN -> JBColor(0xBF8700, 0xD29922)
        TargetState.NOT_INSTALLED -> JBColor.GRAY
        null -> JBColor.GRAY
    }

    private fun elide(text: String, max: Int = 56): String =
        if (text.length <= max) text else text.take(max - 1) + "…"

    private fun menuItem(label: String, action: () -> Unit): JMenuItem =
        JMenuItem(label).apply { addActionListener { action() } }

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
