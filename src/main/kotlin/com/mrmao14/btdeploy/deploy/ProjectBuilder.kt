package com.mrmao14.btdeploy.deploy

import com.google.gson.JsonParser
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import com.intellij.task.ProjectTaskManager
import com.mrmao14.btdeploy.api.BtApiException
import org.jetbrains.concurrency.Promise
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

enum class BuildType(val label: String) {
    AUTO("自动识别"),
    IDEA("IDEA Build"),
    MAVEN("Maven package"),
    GRADLE("Gradle build"),
    FRONTEND("前端 build");

    override fun toString() = label

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.name == value } ?: AUTO
    }
}

/** 部署前的构建：IDEA Build 只编译，Maven / Gradle 会真实打包并返回产出的 jar，前端项目返回 build 产物目录。 */
object ProjectBuilder {
    private const val TIMEOUT_MINUTES = 30
    private val SKIP_JARS = listOf(".original", "-sources.jar", "-javadoc.jar", "-plain.jar")
    private val FRONTEND_OUTPUT_DIRS = listOf("dist", "build", "out", ".output/public")
    private val PACKAGE_MANAGERS = listOf("npm", "pnpm", "yarn", "bun")

    fun build(
        project: Project,
        type: BuildType,
        frontendDir: String,
        indicator: ProgressIndicator,
        log: (String) -> Unit,
    ): Path? {
        val base = project.basePath?.let(Paths::get) ?: throw BtApiException("无法获取项目根目录")
        val frontendRoot = resolveFrontendDir(base, frontendDir)
        val actual = if (type == BuildType.AUTO) detect(base, frontendRoot, frontendDir.isNotBlank()) else type
        log("      构建方式：${actual.label}")

        return when (actual) {
            BuildType.MAVEN -> {
                if (!mavenAvailable()) throw BtApiException("未启用 IDEA 的 Maven 插件，无法执行 Maven 构建")
                log("      执行 Maven clean package（输出见 IDEA 的 Maven 控制台）")
                if (!MavenLifecycleRunner.run(project, listOf("clean", "package"), indicator)) {
                    throw BtApiException("Maven 构建失败或已取消，请查看 IDEA 的 Maven 控制台")
                }
                findJar(base.resolve("target"))
            }

            BuildType.GRADLE -> {
                runCommand(project, base, listOf(wrapperOr(base, "gradlew", "gradlew.bat", "gradle", "gradle.bat"), "clean", "build", "-x", "test", "--console=plain"), "Gradle", log)
                findJar(base.resolve("build/libs"))
            }

            BuildType.FRONTEND -> buildFrontend(project, frontendRoot, log)

            else -> {
                buildIdeaProject(project)
                null
            }
        }
    }

    private fun detect(base: Path, frontendRoot: Path, frontendConfigured: Boolean): BuildType = when {
        frontendConfigured -> BuildType.FRONTEND
        Files.exists(base.resolve("pom.xml")) -> BuildType.MAVEN
        Files.exists(base.resolve("build.gradle.kts")) || Files.exists(base.resolve("build.gradle")) -> BuildType.GRADLE
        Files.isRegularFile(frontendRoot.resolve("package.json")) -> BuildType.FRONTEND
        else -> BuildType.IDEA
    }

    private fun resolveFrontendDir(base: Path, configured: String): Path =
        configured.trim().takeIf { it.isNotEmpty() }?.let { base.resolve(it).normalize() } ?: base

    /** 读取 package.json 的 packageManager 或 lock 文件选择包管理器，默认 npm。 */
    private fun packageManager(dir: Path): String {
        val declared = runCatching {
            JsonParser.parseString(Files.readString(dir.resolve("package.json")))
                .asJsonObject.get("packageManager")?.asString?.substringBefore('@')
        }.getOrNull()
        if (declared != null && declared in PACKAGE_MANAGERS) return declared

        return when {
            Files.exists(dir.resolve("pnpm-lock.yaml")) -> "pnpm"
            Files.exists(dir.resolve("yarn.lock")) -> "yarn"
            Files.exists(dir.resolve("bun.lockb")) || Files.exists(dir.resolve("bun.lock")) -> "bun"
            else -> "npm"
        }
    }

    /** Windows 下 npm / pnpm / yarn 是 .cmd 脚本，需要交给 cmd.exe 执行。 */
    private fun managerExe(manager: String): String =
        if (SystemInfo.isWindows && manager != "bun") "$manager.cmd" else manager

    private fun buildFrontend(project: Project, dir: Path, log: (String) -> Unit): Path {
        if (!Files.isRegularFile(dir.resolve("package.json"))) {
            throw BtApiException("前端目录下没有 package.json：$dir")
        }
        val manager = packageManager(dir)
        val exe = managerExe(manager)
        log("      前端目录：$dir")
        if (!Files.isDirectory(dir.resolve("node_modules"))) {
            runCommand(project, dir, listOf(exe, "install"), manager, log)
        }
        runCommand(project, dir, listOf(exe, "run", "build"), manager, log)

        return FRONTEND_OUTPUT_DIRS.asSequence()
            .map(dir::resolve)
            .firstOrNull { Files.isDirectory(it) }
            ?: throw BtApiException("前端构建完成但未找到产物目录（${FRONTEND_OUTPUT_DIRS.joinToString(" / ")}），请把产物目录加入本地路径")
    }

    private fun mavenAvailable(): Boolean =
        runCatching { Class.forName("org.jetbrains.idea.maven.execution.MavenRunner") }.isSuccess

    private fun wrapperOr(base: Path, unix: String, windows: String, fallbackUnix: String, fallbackWindows: String): String {
        val isWindows = SystemInfo.isWindows
        val wrapper = base.resolve(if (isWindows) windows else unix)
        return when {
            Files.isRegularFile(wrapper) -> wrapper.toString()
            isWindows -> fallbackWindows
            else -> fallbackUnix
        }
    }



    private fun runCommand(project: Project, base: Path, args: List<String>, name: String, log: (String) -> Unit) {
        val exe = args.first()
        val command = if (SystemInfo.isWindows && (exe.endsWith(".cmd", true) || exe.endsWith(".bat", true))) {
            listOf("cmd.exe", "/c") + args
        } else {
            args
        }

        val commandLine = GeneralCommandLine(command)
            .withWorkDirectory(base.toFile())
            .withCharset(consoleCharset())
            .withRedirectErrorStream(true)
        ProjectRootManager.getInstance(project).projectSdk?.homePath?.let { home ->
            commandLine.withEnvironment("JAVA_HOME", home)
            commandLine.withEnvironment("PATH", File(home, "bin").path + File.pathSeparator + System.getenv("PATH").orEmpty())
        }

        log("      执行：${args.joinToString(" ")}")
        val handler = try {
            OSProcessHandler(commandLine)
        } catch (e: Exception) {
            throw BtApiException("$name 启动失败（请确认已安装或存在 wrapper）：${e.message}")
        }
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                event.text.trimEnd().lineSequence().filter { it.isNotBlank() }.forEach(log)
            }
        })
        handler.startNotify()

        if (!handler.waitFor(TimeUnit.MINUTES.toMillis(TIMEOUT_MINUTES.toLong()))) {
            handler.destroyProcess()
            throw BtApiException("$name 构建超时（${TIMEOUT_MINUTES} 分钟）")
        }
        if (handler.exitCode != 0) throw BtApiException("$name 构建失败（退出码 ${handler.exitCode}），请查看部署日志")
    }

    /** target / build/libs 下最新的可执行 jar，排除 sources、javadoc、*-plain 等附属包。 */
    private fun findJar(dir: Path): Path? {
        if (!Files.isDirectory(dir)) return null
        Files.newDirectoryStream(dir, "*.jar").use { entries ->
            return entries
                .filter { Files.isRegularFile(it) }
                .filter { path -> SKIP_JARS.none { path.fileName.toString().endsWith(it, true) } }
                .maxByOrNull { Files.getLastModifiedTime(it).toMillis() }
        }
    }

    /** Windows 中文环境下子进程输出是 GBK，按系统编码解码避免日志乱码。 */
    private fun consoleCharset(): Charset =
        System.getProperty("native.encoding")?.let { runCatching { Charset.forName(it) }.getOrNull() }
            ?: Charsets.UTF_8

    private fun buildIdeaProject(project: Project) {
        var promise: Promise<ProjectTaskManager.Result>? = null
        try {
            ApplicationManager.getApplication().invokeAndWait {
                FileDocumentManager.getInstance().saveAllDocuments()
                promise = ProjectTaskManager.getInstance(project).buildAllModules()
            }
        } catch (e: Exception) {
            throw BtApiException("无法启动 IDEA Build：${e.message}")
        }

        val result = try {
            promise!!.blockingGet(TIMEOUT_MINUTES, TimeUnit.MINUTES)
        } catch (e: Exception) {
            throw BtApiException("IDEA Build 执行失败：${e.message}")
        } ?: throw BtApiException("IDEA Build 未返回结果")

        if (result.isAborted) throw BtApiException("IDEA Build 已取消")
        if (result.hasErrors()) throw BtApiException("IDEA Build 失败，请先修复编译错误")
    }
}
