package com.mrmao14.btdeploy.settings

import java.util.concurrent.ConcurrentHashMap

/** 每个部署配置的最近日志，放在项目内存里，IDE 关闭后清空。 */
class BtDeployLogStore {
    private val logs = ConcurrentHashMap<String, StringBuilder>()

    fun append(targetId: String, line: String) {
        if (targetId.isBlank()) return
        val builder = logs.computeIfAbsent(targetId) { StringBuilder() }
        synchronized(builder) {
            builder.append(line).append('\n')
            if (builder.length > MAX_LENGTH) {
                builder.delete(0, builder.length - MAX_LENGTH)
            }
        }
    }

    fun text(targetId: String): String =
        logs[targetId]?.let { builder -> synchronized(builder) { builder.toString() } }.orEmpty()

    fun clear(targetId: String) {
        logs.remove(targetId)
    }

    private companion object {
        const val MAX_LENGTH = 200_000
    }
}
