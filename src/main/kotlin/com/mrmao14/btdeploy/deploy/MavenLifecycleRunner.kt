package com.mrmao14.btdeploy.deploy

import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.util.Consumer
import org.jetbrains.idea.maven.execution.MavenRunner
import org.jetbrains.idea.maven.execution.MavenRunnerParameters
import org.jetbrains.idea.maven.project.MavenProjectsManager
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 通过 IDEA 的 Maven 集成执行生命周期，等价于在 Maven 工具窗口点击 clean / package。 */
object MavenLifecycleRunner {
    private const val TIMEOUT_MINUTES = 30L
    private const val ATTACH_TIMEOUT_MS = 60_000L

    /** 同步等待 Maven 进程结束并返回是否构建成功；输出显示在 IDEA 的 Maven 控制台。 */
    fun run(project: Project, goals: List<String>, indicator: ProgressIndicator): Boolean {
        val base = project.basePath ?: return false
        val manager = MavenProjectsManager.getInstance(project)
        val parameters = MavenRunnerParameters(false, base, null, goals, manager.explicitProfiles)
        val runner = MavenRunner.getInstance(project)
        val settings = runner.settings.clone().apply { isSkipTests = true }
        val handlerRef = AtomicReference<ProcessHandler>()

        val started = runner.runBatch(
            listOf(parameters),
            manager.generalSettings,
            settings,
            "Maven",
            indicator,
            Consumer<ProcessHandler> { handler -> handlerRef.set(handler) },
        )
        if (!started) return false

        val handler = attachHandler(handlerRef, indicator) ?: return false
        val deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(TIMEOUT_MINUTES)
        while (!handler.isProcessTerminated) {
            if (indicator.isCanceled || System.currentTimeMillis() > deadline) {
                handler.destroyProcess()
                return false
            }
            handler.waitFor(500)
        }
        return handler.exitCode == 0
    }

    /** runBatch 通过 ProgramRunner 异步启动进程，等 onAttach 回调拿到 ProcessHandler。 */
    private fun attachHandler(ref: AtomicReference<ProcessHandler>, indicator: ProgressIndicator): ProcessHandler? {
        val deadline = System.currentTimeMillis() + ATTACH_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline && !indicator.isCanceled) {
            ref.get()?.let { return it }
            if (!sleep(50)) return null
        }
        return null
    }

    private fun sleep(millis: Long): Boolean =
        try {
            Thread.sleep(millis)
            true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
}
