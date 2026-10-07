package io.legado.app.model

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 阅读位置拥有自己的任务，取消后旧调用不能借用新位置的任务范围。 */
internal class CurrentChapterRequests {
    class Request(val bookUrl: String, val chapterIndex: Int) {
        private val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)

        fun cancel() = job.cancel()

        suspend fun <T> run(block: suspend CoroutineScope.() -> T): T = coroutineScope {
            val task = scope.async(block = block)
            try {
                task.await()
            } finally {
                // 调用者取消时也结束这次工作，但不取消独立的手动缓存任务。
                task.cancel()
            }
        }

        suspend fun <T> content(
            index: Int,
            cached: suspend () -> T?,
            download: suspend () -> T,
        ): T? {
            currentCoroutineContext().ensureActive()
            job.ensureActive()
            cached()?.let { return it }
            if (index != chapterIndex) return null
            currentCoroutineContext().ensureActive()
            job.ensureActive()
            return download()
        }
    }

    private var current: Request? = null

    @Synchronized
    fun select(bookUrl: String, chapterIndex: Int): Request {
        current?.takeIf { it.bookUrl == bookUrl && it.chapterIndex == chapterIndex }?.let { return it }
        current?.cancel()
        return Request(bookUrl, chapterIndex).also { current = it }
    }

    @Synchronized
    fun isCurrent(request: Request): Boolean = current === request

    @Synchronized
    fun cancel() {
        current?.cancel()
        current = null
    }
}
