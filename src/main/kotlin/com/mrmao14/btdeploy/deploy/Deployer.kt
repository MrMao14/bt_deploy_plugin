package com.mrmao14.btdeploy.deploy

import com.google.gson.JsonObject
import com.mrmao14.btdeploy.api.BtApiException
import com.mrmao14.btdeploy.api.BtClient
import com.mrmao14.btdeploy.api.RemoteFile
import java.nio.file.Files
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

enum class RestartType(val label: String) {
    NONE("不重启"),
    WEBSERVER_RELOAD("重载 Web 服务器"),
    SERVICE_RESTART("重启系统服务"),
    JAVA_RESTART("重启 Java 项目");

    override fun toString() = label

    companion object {
        fun from(value: String?): RestartType = entries.firstOrNull { it.name == value } ?: NONE
    }
}

data class DeployConfig(
    val sources: List<Path>,
    val remoteDir: String,
    val keepRoot: Boolean,
    val restart: RestartType,
    val serviceName: String,
    val projectName: String,
)

object Deployer {
    private const val BAK_DIR = "bak"
    private const val BAK_KEEP = 3
    private const val ZIP_PREFIX = "btdeploy-"

    /** 打包 → 上传到远程 bak/ → 解压 → 清理旧备份 → 重启。 */
    fun deploy(client: BtClient, config: DeployConfig, log: (String) -> Unit) {
        val sources = config.sources.map { it.toAbsolutePath().normalize() }
        if (sources.isEmpty()) throw BtApiException("没有配置本地路径")
        val missing = sources.filterNot { Files.exists(it) }
        if (missing.isNotEmpty()) throw BtApiException("本地路径不存在：" + missing.joinToString("、"))

        val remoteDir = config.remoteDir.trim().trimEnd('/')
        if (remoteDir.isEmpty()) throw BtApiException("远程目标目录不能为空")

        val workDir = Files.createTempDirectory("bt-deploy-")
        try {
            log("[1/5] 打包 ${sources.size} 个路径")
            sources.forEach { log("      · $it") }
            val zip = buildZip(sources, workDir, config.keepRoot)
            log("      → ${zip.fileName}（${"%.2f".format(Locale.ROOT, Files.size(zip) / 1024.0 / 1024.0)} MB）")

            val bak = "$remoteDir/$BAK_DIR"
            log("[2/5] 上传到 $bak")
            client.ensureDir(bak)
            val uploadName = ZIP_PREFIX + timestamp() + ".zip"
            client.uploadFile(bak, uploadName, Files.readAllBytes(zip))
            log("      上传成功")

            log("[3/5] 解压到 $remoteDir")
            checkUnzip(client.unzip("$bak/$uploadName", remoteDir))
            log("      解压完成")

            log("[4/5] 清理旧备份（最多保留 $BAK_KEEP 份）")
            try {
                val removed = pruneBackups(client, remoteDir)
                log(if (removed > 0) "      清理了 $removed 个旧备份" else "      没有超出保留份数")
            } catch (e: BtApiException) {
                log("      清理失败（不影响部署结果）：${e.message}")
            }

            log("[5/5] 重启服务")
            restart(client, config, log)
        } finally {
            workDir.toFile().deleteRecursively()
        }
    }

    fun listBackups(client: BtClient, remoteDir: String): List<RemoteFile> =
        client.listDir("${remoteDir.trim().trimEnd('/')}/$BAK_DIR")
            .filter { it.name.startsWith(ZIP_PREFIX) && it.name.endsWith(".zip") }
            .sortedByDescending { it.mtime }

    /** 把 bak/ 里指定的部署包重新解压到远程目录，并执行配置的部署后动作。 */
    fun restoreBackup(client: BtClient, config: DeployConfig, filename: String, log: (String) -> Unit) {
        val remoteDir = config.remoteDir.trim().trimEnd('/')
        if (remoteDir.isEmpty()) throw BtApiException("远程目标目录不能为空")

        log("[1/2] 解压备份 $filename 到 $remoteDir")
        checkUnzip(client.unzip("$remoteDir/$BAK_DIR/$filename", remoteDir))
        log("      解压完成")

        log("[2/2] 重启服务")
        restart(client, config, log)
    }

    private fun buildZip(sources: List<Path>, workDir: Path, keepRoot: Boolean): Path {
        // 只有一个 zip 时直接上传，省掉解压再压缩
        if (sources.size == 1 && Files.isRegularFile(sources[0]) &&
            sources[0].fileName.toString().endsWith(".zip", ignoreCase = true)
        ) {
            return sources[0]
        }

        val zip = workDir.resolve(ZIP_PREFIX + timestamp() + ".zip")
        val seen = HashSet<String>()
        ZipOutputStream(Files.newOutputStream(zip)).use { out ->
            sources.forEach { addSource(out, it, seen, keepRoot) }
        }
        return zip
    }

    private fun addSource(out: ZipOutputStream, source: Path, seen: MutableSet<String>, keepRoot: Boolean) {
        when {
            Files.isDirectory(source) -> {
                val prefix = if (keepRoot) source.fileName.toString() + "/" else ""
                Files.walk(source).use { paths ->
                    paths.filter { Files.isRegularFile(it) && !Files.isSymbolicLink(it) }
                        .forEach { file ->
                            val relative = source.relativize(file).joinToString("/") { it.toString() }
                            val name = prefix + relative
                            if (seen.add(name)) writeEntry(out, file, name)
                        }
                }
            }

            Files.isRegularFile(source) -> {
                if (source.fileName.toString().endsWith(".zip", ignoreCase = true)) {
                    ZipFile(source.toFile()).use { inner ->
                        inner.entries().asSequence().forEach { entry ->
                            if (!entry.isDirectory && seen.add(entry.name)) {
                                out.putNextEntry(ZipEntry(entry.name))
                                inner.getInputStream(entry).use { it.copyTo(out) }
                                out.closeEntry()
                            }
                        }
                    }
                } else {
                    val name = source.fileName.toString()
                    if (seen.add(name)) writeEntry(out, source, name)
                }
            }

            else -> throw BtApiException("本地路径不是文件或目录：$source")
        }
    }

    private fun writeEntry(out: ZipOutputStream, file: Path, name: String) {
        out.putNextEntry(ZipEntry(name))
        Files.newInputStream(file).use { it.copyTo(out) }
        out.closeEntry()
    }

    private fun pruneBackups(client: BtClient, remoteDir: String): Int {
        val backups = listBackups(client, remoteDir)
        backups.drop(BAK_KEEP).forEach { client.delete("$remoteDir/$BAK_DIR/${it.name}") }
        return (backups.size - BAK_KEEP).coerceAtLeast(0)
    }

    private fun restart(client: BtClient, config: DeployConfig, log: (String) -> Unit) {
        when (config.restart) {
            RestartType.NONE -> log("      按配置跳过重启")

            RestartType.WEBSERVER_RELOAD -> {
                client.serviceAdmin("webserver", "reload")
                log("      已重载 Web 服务器")
            }

            RestartType.SERVICE_RESTART -> {
                val name = config.serviceName.trim()
                if (name.isEmpty()) throw BtApiException("重启方式为「重启系统服务」时必须填写服务名，例如 nginx")
                client.serviceAdmin(name, "restart")
                log("      已重启服务 $name")
            }

            RestartType.JAVA_RESTART -> {
                val name = config.projectName.trim()
                if (name.isEmpty()) throw BtApiException("重启方式为「重启 Java 项目」时必须填写项目名称")
                client.javaProjectAction(name)
                log("      已下发重启指令：$name")
            }
        }
    }

    private fun checkUnzip(result: com.google.gson.JsonElement) {
        val fail = (result as? JsonObject)?.get("fail") ?: return
        val text = if (fail.isJsonPrimitive) fail.asString.trim() else ""
        if (text.isNotEmpty() && text != "false" && text != "null") {
            throw BtApiException("解压失败：$text")
        }
    }

    private fun timestamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
}
