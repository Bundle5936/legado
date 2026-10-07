package io.legado.app.model

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class CurrentChapterRequestsTest {
    @Test
    fun `仅当前章缓存缺失时发送网络请求`() = runBlocking {
        val requests = CurrentChapterRequests()
        val current = requests.select("book", 10)
        val downloads = mutableListOf<Int>()
        try {
            for (index in listOf(9, 10, 11, 12)) {
                val text = current.run {
                    current.content(index, cached = { if (index == 9) "cached" else null }) {
                        downloads.add(index)
                        "downloaded"
                    }
                }
                assertEquals(if (index == 10) "downloaded" else null, text)
            }
            assertEquals(listOf(10), downloads)
            assertEquals("local PDF", current.run {
                current.content(9, localBook = true, cached = { "local PDF" }) { error("本地排版不能联网") }
            })
            assertNull(current.run {
                current.content(9, localBook = true, cached = { null }) { error("相邻章不能下载") }
            })
            assertEquals("cached current", current.run {
                current.content(10, cached = { "cached current" }) { error("不应重新下载") }
            })
            assertSame(current, requests.select("book", 10))
        } finally {
            requests.cancel()
        }
    }

    @Test
    fun `跳章取消旧下载并阻止旧任务延后启动`() = runBlocking {
        withTimeout(5000) {
            val requests = CurrentChapterRequests()
            val old = requests.select("book", 10)
            val started = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            val running = async {
                old.run {
                    started.complete(Unit)
                    try { awaitCancellation() } finally { stopped.complete(Unit) }
                }
            }
            started.await()
            val fresh = requests.select("book", 20)
            stopped.await()
            assertTrue(runCatching { running.await() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
            var lateRequest = false
            assertTrue(runCatching { old.run { lateRequest = true } }.isFailure)
            assertFalse(lateRequest)
            assertFalse(requests.isCurrent(old))
            assertTrue(requests.isCurrent(fresh))
            assertEquals("new", fresh.run { "new" })
            val back = requests.select("book", 10)
            assertNotSame(old, back)
            requests.cancel()
        }
    }

    @Test
    fun `取消等待者结束自身任务但不影响独立手动缓存`() = runBlocking {
        withTimeout(5000) {
            val requests = CurrentChapterRequests()
            val current = requests.select("book", 1)
            val started = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            val manual = async(start = CoroutineStart.UNDISPATCHED) { awaitCancellation() }
            val waiter = async {
                current.run {
                    started.complete(Unit)
                    try { awaitCancellation() } finally { stopped.complete(Unit) }
                }
            }
            started.await()
            waiter.cancelAndJoin()
            stopped.await()
            assertTrue(manual.isActive)
            assertEquals("still usable", current.run { "still usable" })
            requests.cancel()
            manual.cancelAndJoin()
        }
    }
}
