package io.javalin.util

import io.javalin.util.LoomUtil.loomAvailable
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.util.thread.QueuedThreadPool
import org.eclipse.jetty.util.thread.ThreadPool
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

object ConcurrencyUtil {

    var useLoom = true

    @JvmStatic
    fun executorService(name: String): ExecutorService = when (useLoom && loomAvailable) {
        true -> LoomUtil.getExecutorService(name)
        false -> Executors.newCachedThreadPool(NamedThreadFactory(name))
    }

    fun newSingleThreadScheduledExecutor(name: String): ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor(NamedThreadFactory(name))

    fun jettyThreadPool(name: String): ThreadPool = when (useLoom && loomAvailable) {
        true -> LoomThreadPool(name)
        false -> QueuedThreadPool(250, 8, 60_000).apply { this.name = name }
    }
}

internal class LoomThreadPool(name: String) : ThreadPool {
    private val executorService = LoomUtil.getExecutorService(name)
    override fun join() {}
    override fun getThreads() = 1
    override fun getIdleThreads() = 1
    override fun isLowOnThreads() = false
    override fun execute(command: Runnable) {
        executorService.submit(command)
    }
}

internal object LoomUtil {

    val loomAvailable = runCatching { getExecutorService("") }.isSuccess

    fun getExecutorService(name: String): ExecutorService { // we should use this name when we figure out how
        val factoryMethod = Executors::class.java.getMethod("newVirtualThreadPerTaskExecutor") // this will not throw if preview is not enabled
        return factoryMethod.invoke(Executors::class.java) as ExecutorService // this *will* throw if preview is not enabled
    }

    val logMsg = "Your JDK supports Loom. Javalin will prefer Virtual Threads by default. Disable with `ConcurrencyUtil.useLoom = false`."

    fun logIfLoom(server: Server) {
        if (server.threadPool !is LoomThreadPool) return
        JavalinLogger.startup(logMsg)
    }

}

internal class NamedThreadFactory(private val prefix: String) : ThreadFactory {
    private val group = Thread.currentThread().threadGroup
    private val threadCount = AtomicInteger(0)
    override fun newThread(runnable: Runnable): Thread =
        Thread(group, runnable, "$prefix-${threadCount.getAndIncrement()}", 0)
}
