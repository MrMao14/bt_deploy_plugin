package com.mrmao14.btdeploy.deploy

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mrmao14.btdeploy.api.BtClient
import com.mrmao14.btdeploy.settings.BtDeploySettings.TargetConfig

enum class TargetState(val label: String) {
    RUNNING("运行中"),
    STOPPED("已停止"),
    UNKNOWN("未知"),
    NOT_INSTALLED("未安装"),
}

data class TargetStatus(val state: TargetState)

/** 一次把当前面板下所有部署配置的运行状态查回来。 */
object StatusService {
    private val SERVICE_STATUS_KEYS = mapOf(
        "nginx" to "web", "httpd" to "web", "apache" to "web", "webserver" to "web",
        "openlitespeed" to "web",
        "mysqld" to "mysql", "mysql" to "mysql", "mariadb" to "mysql",
        "redis" to "redis", "memcached" to "memcached", "pure-ftpd" to "pure-ftpd",
        "tomcat" to "tomcat",
    )

    fun load(client: BtClient, targets: List<TargetConfig>): Map<String, TargetStatus> {
        val kinds = targets.map { RestartType.from(it.restartType) }
        val needJava = kinds.any { it == RestartType.JAVA_RESTART }
        // 不重启的静态站也显示 Web 服务器状态
        val needEnv = kinds.any { it != RestartType.JAVA_RESTART }

        val javaStatuses = if (needJava) {
            runCatching { client.listJavaProjects() }
                .getOrDefault(emptyList())
                .associate { it.name to it.running }
        } else {
            emptyMap()
        }
        val env = if (needEnv) runCatching { client.configInfo() }.getOrNull() else null

        return targets.associate { target ->
            val type = RestartType.from(target.restartType)
            val status = when (type) {
                RestartType.NONE -> serviceStatus(env, "web")

                RestartType.JAVA_RESTART -> {
                    val project = target.javaProjectName.trim()
                    if (project.isEmpty()) {
                        TargetStatus(TargetState.UNKNOWN)
                    } else {
                        statusOf(javaStatuses[project])
                    }
                }

                RestartType.WEBSERVER_RELOAD -> serviceStatus(env, "web")

                RestartType.SERVICE_RESTART -> {
                    val name = target.serviceName.trim().lowercase()
                    if (name.isEmpty()) {
                        TargetStatus(TargetState.UNKNOWN)
                    } else {
                        serviceStatus(env, SERVICE_STATUS_KEYS[name] ?: "web")
                    }
                }
            }
            target.id to status
        }
    }

    private fun statusOf(running: Boolean?): TargetStatus = when (running) {
        true -> TargetStatus(TargetState.RUNNING)
        false -> TargetStatus(TargetState.STOPPED)
        null -> TargetStatus(TargetState.UNKNOWN)
    }

    private fun serviceStatus(env: JsonElement?, key: String): TargetStatus {
        val root = env as? JsonObject ?: return TargetStatus(TargetState.UNKNOWN)
        val entry = root.get(key) as? JsonObject ?: return TargetStatus(TargetState.UNKNOWN)
        val setup = entry.get("setup")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
            ?.asBoolean
        if (setup == false) return TargetStatus(TargetState.NOT_INSTALLED)
        return statusOf(entry.get("status")?.toRunning())
    }

    private fun JsonElement.toRunning(): Boolean? {
        if (!isJsonPrimitive) return null
        val primitive = asJsonPrimitive
        if (primitive.isBoolean) return primitive.asBoolean
        if (primitive.isNumber) return primitive.asInt != 0
        return when (primitive.asString.trim().lowercase()) {
            "1", "true", "on", "running", "start", "started" -> true
            "0", "false", "off", "stopped", "stop", "notrunning" -> false
            else -> null
        }
    }
}
