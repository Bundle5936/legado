package io.legado.app.ui.book.read

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.iki.elonen.NanoHTTPD
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.constant.PageAnim
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.data.entities.rule.ReviewRule
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.LocalConfig
import io.legado.app.model.CacheBook
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.page.entities.column.ReviewColumn
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.utils.defaultSharedPreferences
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicReference

/** 只用本机假书源验证网络请求和真实阅读页，不访问外部小说账号。 */
@RunWith(AndroidJUnit4::class)
class ConservativeReadingUiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs = context.defaultSharedPreferences
    private val savedPrefs = listOf(PreferKey.preDownloadNum, PreferKey.cronet, PreferKey.autoBackup)
        .associateWith { prefs.all[it] }
    private val savedHelp = listOf("readHelpVersion", "readMenuHelpVersion").associateWith { LocalConfig.all[it] }
    private val bodyRequests = AtomicIntegerArray(4)
    private val summaryRequests = AtomicIntegerArray(4)
    private val blockFirstBody = AtomicBoolean(false)
    private val failFirstSummary = AtomicBoolean(false)
    private val bodyGate = CountDownLatch(1)
    private val summaryGate = CountDownLatch(1)
    private val id = UUID.randomUUID().toString()
    private val server = object : NanoHTTPD("127.0.0.1", 0) {
        override fun serve(session: IHTTPSession): Response {
            val index = session.uri.substringAfterLast('/').toIntOrNull()
                ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "missing")
            if (session.uri.startsWith("/body/")) {
                bodyRequests.incrementAndGet(index)
                if (index == 0 && blockFirstBody.get()) bodyGate.await(20, TimeUnit.SECONDS)
                val text = (1..18).joinToString("") { "<p>正文 $index 段落 $it，测试阅读位置与段评泡泡。</p>" }
                return newFixedLengthResponse(Response.Status.OK, "text/html", "<html><body>$text</body></html>")
            }
            summaryRequests.incrementAndGet(index)
            if (failFirstSummary.compareAndSet(true, false)) {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "temporary failure")
            }
            summaryGate.await(20, TimeUnit.SECONDS)
            return newFixedLengthResponse(Response.Status.OK, "application/json",
                """{"items":[{"index":0,"id":"title","count":7},{"index":1,"id":"first","count":7},{"index":2,"id":"second","count":7}]}""")
        }
    }
    private val source = BookSource(bookSourceUrl = "https://conservative.invalid/$id",
        bookSourceName = "保守阅读测试", ruleContent = ContentRule(content = "body@text"),
        ruleReview = ReviewRule(enabled = true,
            reviewSummaryUrl = "@js: baseUrl.replace('/body/', '/summary/');",
            summaryListRule = "$.items[*]", summaryParagraphIndexRule = "$.index",
            summaryParagraphDataRule = "$.id", summaryCountRule = "$.count"))
    private val book = Book(bookUrl = "https://conservative.invalid/book/$id", origin = source.bookSourceUrl,
        name = "保守阅读 $id", author = "Fixture", type = BookType.text, totalChapterNum = 4,
        canUpdate = false).apply { setPageAnim(PageAnim.noAnim); setUseReplaceRule(false) }
    private lateinit var chapters: List<BookChapter>
    private var scenario: ActivityScenario<ReadBookActivity>? = null

    @Before fun prepare() {
        // 即使用户原来设了大量预读，也不能发出相邻章节网络请求。
        prefs.edit().putInt(PreferKey.preDownloadNum, 40).putBoolean(PreferKey.cronet, false)
            .putBoolean(PreferKey.autoBackup, false).commit()
        LocalConfig.edit().putInt("readHelpVersion", 1).putInt("readMenuHelpVersion", 1).commit()
        server.start()
        chapters = (0..3).map { BookChapter(bookUrl = book.bookUrl, index = it, title = "章节 $it",
            url = "http://127.0.0.1:${server.listeningPort}/body/$it", baseUrl = book.bookUrl) }
        appDb.bookSourceDao.insert(source)
        appDb.bookDao.insert(book)
        appDb.bookChapterDao.insert(*chapters.toTypedArray())
    }

    @After fun cleanup() {
        bodyGate.countDown()
        summaryGate.countDown()
        scenario?.close()
        server.stop()
        chapters.forEach { BookHelp.delContent(book, it) }
        CacheBook.cacheBookMap.remove(book.bookUrl)?.stop()
        appDb.bookChapterDao.delByBook(book.bookUrl)
        appDb.bookDao.delete(book)
        appDb.bookSourceDao.delete(source)
        prefs.edit().apply { savedPrefs.forEach { (key, value) -> when (value) {
            null -> remove(key)
            is Int -> putInt(key, value)
            is Boolean -> putBoolean(key, value)
        } } }.commit()
        LocalConfig.edit().apply { savedHelp.forEach { (key, value) ->
            if (value == null) remove(key) else putInt(key, value as Int)
        } }.commit()
    }

    @Test fun delayedCommentsAppearWithoutReloadingBodyOrFetchingNeighbours() {
        launch()
        awaitReader(0)
        await("当前章摘要开始请求") { summaryRequests.get(0) == 1 }
        assertFalse(main { ChapterProvider.hasReviewCountProvider(0) })
        val position = main { ReadBook.durChapterPos }
        summaryGate.countDown()
        awaitBubbles(0)
        assertEquals(position, main { ReadBook.durChapterPos })
        assertEquals(1, bodyRequests.get(0))
        assertEquals(1, summaryRequests.get(0))
        for (index in 1..3) {
            assertEquals(0, bodyRequests.get(index))
            assertEquals(0, summaryRequests.get(index))
        }
        // 正常翻章也只在移动到下一章之后下载。
        main { assertTrue(ReadBook.moveToNextChapter(true)) }
        awaitReader(1)
        awaitBubbles(1)
        assertEquals(1, bodyRequests.get(1))
        assertEquals(0, bodyRequests.get(2))
    }

    @Test fun chapterJumpDiscardsSlowOldBodyWithoutStartingAdjacentDownloads() {
        blockFirstBody.set(true)
        summaryGate.countDown()
        launch()
        await("旧章正文请求开始") { bodyRequests.get(0) == 1 }
        main { ReadBook.openChapter(2) }
        awaitReader(2)
        bodyGate.countDown()
        awaitBubbles(2)
        assertEquals(2, main { ReadBook.curTextChapter!!.chapter.index })
        assertEquals(0, bodyRequests.get(1))
        assertEquals(0, bodyRequests.get(3))
        assertEquals(0, summaryRequests.get(0))
        assertEquals(1, bodyRequests.get(2))
    }

    @Test fun retryCommentsDoesNotRefreshBodyOrRetryOnPageRedraw() {
        failFirstSummary.set(true)
        summaryGate.countDown()
        launch()
        awaitReader(0)
        await("段评失败提示") { main { it.findViewById<android.widget.TextView>(
            com.google.android.material.R.id.snackbar_text)?.text == context.getString(R.string.review_summary_failed) } }
        main { ReadBook.callBack?.upContent() }
        assertEquals(1, summaryRequests.get(0))
        onView(withText(R.string.review_summary_retry)).perform(click())
        awaitBubbles(0)
        assertEquals(2, summaryRequests.get(0))
        assertEquals(1, bodyRequests.get(0))
    }

    private fun launch() {
        scenario = ActivityScenario.launch(Intent(context, ReadBookActivity::class.java)
            .putExtra("bookUrl", book.bookUrl).putExtra("inBookshelf", true))
    }

    private fun awaitReader(index: Int) = await("正文 $index") { main {
        val chapter = ReadBook.curTextChapter
        ReadBook.book?.bookUrl == book.bookUrl && ReadBook.durChapterIndex == index &&
            chapter?.chapter?.index == index && chapter.hasBodyContent && chapter.isCompleted && it.isInitFinish
    } }

    private fun awaitBubbles(index: Int) = await("自动显示泡泡 $index") { main {
        ChapterProvider.hasReviewCountProvider(index) && ReadBook.curTextChapter?.pages?.any { page ->
            page.lines.any { line -> line.columns.any { column -> column is ReviewColumn } }
        } == true
    } }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        throw AssertionError("超时：$message")
    }

    private fun <T> main(block: (ReadBookActivity) -> T): T {
        val result = AtomicReference<T>()
        scenario!!.onActivity { result.set(block(it)) }
        return result.get()
    }
}
