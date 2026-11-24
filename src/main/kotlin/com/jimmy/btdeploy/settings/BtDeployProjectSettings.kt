package com.jimmy.btdeploy.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.util.UUID

/** 当前项目的面板与部署配置，保存在 .idea/bt-deploy.xml。 */
@State(name = "BtDeployProjectSettings", storages = [Storage("bt-deploy.xml")])
class BtDeployProjectSettings : PersistentStateComponent<BtDeployProjectSettings.State> {
    class State {
        var panels: MutableList<PanelConfig> = mutableListOf()
        var activePanelId: String = ""
        var targets: MutableList<TargetConfig> = mutableListOf()
        var selectedTargetId: String = ""
    }

    class PanelConfig {
        var id: String = ""
        var name: String = ""
        var panelUrl: String = ""
        var apiSk: String = ""
        var verifySsl: Boolean = false

        fun copy(): PanelConfig = PanelConfig().also {
            it.id = id
            it.name = name
            it.panelUrl = panelUrl
            it.apiSk = apiSk
            it.verifySsl = verifySsl
        }

        override fun toString(): String = name
    }

    class TargetConfig {
        var id: String = ""
        var panelId: String = ""
        var name: String = ""
        var localPaths: MutableList<String> = mutableListOf()
        var remoteDir: String = ""
        var keepRoot: Boolean = true
        var restartType: String = "NONE"
        var serviceName: String = ""
        var javaProjectName: String = ""

        fun copy(): TargetConfig = TargetConfig().also {
            it.id = id
            it.panelId = panelId
            it.name = name
            it.remoteDir = remoteDir
            it.keepRoot = keepRoot
            it.restartType = restartType
            it.serviceName = serviceName
            it.javaProjectName = javaProjectName
            it.localPaths = ArrayList(localPaths)
        }

        override fun toString(): String = name
    }

    private var myState = State().also(::ensureDefaults)

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state.also(::ensureDefaults)
    }

    private fun ensureDefaults(state: State) {
        state.panels.forEach { if (it.id.isBlank()) it.id = newId() }
        if (state.panels.isEmpty()) state.panels.add(createPanel("默认面板"))
        state.panels.forEachIndexed { index, panel ->
            if (panel.name.isBlank()) panel.name = "面板 ${index + 1}"
        }

        if (state.panels.none { it.id == state.activePanelId }) {
            state.activePanelId = state.panels.first().id
        }

        state.targets.forEach { if (it.id.isBlank()) it.id = newId() }
        state.targets.forEachIndexed { index, target ->
            if (target.name.isBlank()) target.name = "配置 ${index + 1}"
        }
        val panelIds = state.panels.map { it.id }.toSet()
        state.targets.removeAll { it.panelId !in panelIds }
        if (state.targets.none { it.id == state.selectedTargetId }) {
            state.selectedTargetId = ""
        }
    }

    fun nextPanelName(): String {
        var index = myState.panels.size + 1
        while (myState.panels.any { it.name == "面板 $index" }) index++
        return "面板 $index"
    }

    fun nextTargetName(panelId: String): String {
        val used = myState.targets.filter { it.panelId == panelId }.map { it.name }.toSet()
        var index = used.size + 1
        while ("配置 $index" in used) index++
        return "配置 $index"
    }

    companion object {
        fun createPanel(name: String): PanelConfig = PanelConfig().also {
            it.id = newId()
            it.name = name
        }

        fun createTarget(panelId: String, name: String): TargetConfig = TargetConfig().also {
            it.id = newId()
            it.panelId = panelId
            it.name = name
        }

        private fun newId(): String = UUID.randomUUID().toString()
    }
}
