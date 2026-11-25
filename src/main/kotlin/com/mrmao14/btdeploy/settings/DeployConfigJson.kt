package com.mrmao14.btdeploy.settings

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mrmao14.btdeploy.deploy.BuildType
import com.mrmao14.btdeploy.deploy.RestartType
import com.mrmao14.btdeploy.settings.BtDeploySettings.PanelConfig
import com.mrmao14.btdeploy.settings.BtDeploySettings.TargetConfig

/** 兼容桌面版 `bt-deploy/targets` JSON 格式的导入导出。 */
object DeployConfigJson {
    data class ImportResult(
        val added: Int,
        val replaced: Int,
        val selectedTargetId: String?,
        val apiKeyImported: Boolean,
    )

    private data class TargetData(
        val name: String,
        val localPaths: List<String>,
        val buildType: String,
        val deployType: String,
        val buildEnabled: Boolean,
        val frontendDir: String,
        val remoteDir: String,
        val keepRoot: Boolean,
        val restartType: String,
        val serviceName: String,
        val javaProjectName: String,
    )

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun export(panel: PanelConfig, targets: List<TargetConfig>): String {
        val root = JsonObject().apply {
            addProperty("format", "bt-deploy/targets")
            addProperty("version", 1)
            addProperty("panel_url", panel.panelUrl)
            addProperty("verify_ssl", panel.verifySsl)
            add("targets", JsonArray().apply { targets.forEach { add(targetJson(it)) } })
        }
        return gson.toJson(root)
    }

    fun importTargets(settings: BtDeploySettings, panel: PanelConfig, text: String): ImportResult? {
        val root = JsonParser.parseString(text)
        val meta = root as? JsonObject
        val incoming = when (root) {
            is JsonArray -> root
            is JsonObject -> root.get("targets") as? JsonArray
            else -> null
        } ?: return null

        val existing = settings.state.targets
            .filter { it.panelId == panel.id }
            .associateBy { it.name }
            .toMutableMap()
        var added = 0
        var replaced = 0
        var selectedTargetId: String? = null

        incoming.forEach { element ->
            val data = parseTarget(element) ?: return@forEach
            val current = existing[data.name]
            val target = current ?: BtDeploySettings.createTarget(panel.id, data.name).also {
                settings.state.targets.add(it)
                existing[data.name] = it
                added++
            }
            if (current != null) replaced++
            target.apply(data)
            selectedTargetId = target.id
        }

        meta?.text("panel_url")?.takeIf { it.isNotBlank() }?.let { panel.panelUrl = it }
        meta?.bool("verify_ssl")?.let { panel.verifySsl = it }
        val apiKey = meta?.text("api_sk")
        if (!apiKey.isNullOrBlank()) panel.apiSk = apiKey

        return ImportResult(added, replaced, selectedTargetId, !apiKey.isNullOrBlank())
    }

    private fun targetJson(target: TargetConfig): JsonObject {
        val restart = RestartType.from(target.restartType)
        return JsonObject().apply {
            addProperty("name", target.name)
            add("source_dirs", JsonArray().apply { target.localPaths.forEach { add(it) } })
            addProperty("build_type", BuildType.from(target.buildType).name.lowercase())
            addProperty("deploy_type", target.deployType.lowercase())
            addProperty("build_enabled", target.buildEnabled)
            addProperty("frontend_dir", target.frontendDir)
            addProperty("keep_root", target.keepRoot)
            addProperty("remote_dir", target.remoteDir)
            add("restart", JsonObject().apply {
                addProperty("type", restart.wireName())
                when (restart) {
                    RestartType.JAVA_RESTART -> addProperty("project_name", target.javaProjectName)
                    RestartType.SERVICE_RESTART -> addProperty("service_name", target.serviceName)
                    else -> Unit
                }
            })
        }
    }

    private fun parseTarget(element: JsonElement): TargetData? {
        val obj = element as? JsonObject ?: return null
        val name = obj.text("name").orEmpty().trim()
        if (name.isEmpty()) return null

        val sourceDirs = (obj.get("source_dirs") as? JsonArray)
            ?.mapNotNull { item -> item.stringOrNull()?.trim()?.takeIf { it.isNotEmpty() } }
            ?: listOfNotNull(obj.text("source_dir")?.takeIf { it.isNotBlank() })
        val restart = obj.get("restart") as? JsonObject
        val wireType = restart?.text("type") ?: obj.text("restart")

        return TargetData(
            name = name,
            localPaths = sourceDirs,
            buildType = BuildType.from(obj.text("build_type")?.uppercase()).name,
            deployType = obj.text("deploy_type")?.uppercase().orEmpty(),
            buildEnabled = obj.bool("build_enabled") ?: true,
            frontendDir = obj.text("frontend_dir").orEmpty(),
            remoteDir = obj.text("remote_dir").orEmpty(),
            keepRoot = obj.bool("keep_root") ?: true,
            restartType = restartType(wireType).name,
            serviceName = restart?.text("service_name").orEmpty(),
            javaProjectName = restart?.text("project_name").orEmpty(),
        )
    }

    private fun TargetConfig.apply(data: TargetData) {
        localPaths = data.localPaths.toMutableList()
        buildType = data.buildType
        deployType = data.deployType
        buildEnabled = data.buildEnabled
        frontendDir = data.frontendDir
        remoteDir = data.remoteDir
        keepRoot = data.keepRoot
        restartType = data.restartType
        serviceName = data.serviceName
        javaProjectName = data.javaProjectName
    }

    private fun JsonObject.text(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.bool(name: String): Boolean? {
        val primitive = get(name)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
        return if (primitive.isBoolean) primitive.asBoolean else null
    }

    private fun JsonElement.stringOrNull(): String? =
        if (isJsonPrimitive && asJsonPrimitive.isString) asString else null

    private fun RestartType.wireName(): String = when (this) {
        RestartType.NONE -> "none"
        RestartType.WEBSERVER_RELOAD -> "webserver_reload"
        RestartType.SERVICE_RESTART -> "service_restart"
        RestartType.JAVA_RESTART -> "java_restart"
    }

    private fun restartType(value: String?): RestartType = when (value) {
        "webserver_reload" -> RestartType.WEBSERVER_RELOAD
        "service_restart" -> RestartType.SERVICE_RESTART
        "java_restart" -> RestartType.JAVA_RESTART
        else -> RestartType.NONE
    }
}
