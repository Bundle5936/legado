package io.legado.app.ci

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TestReleaseWorkflowTest {

    private val workflowText by lazy {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        val workflowFile = generateSequence(File(userDir)) {
            it.parentFile
        }.map {
            File(it, ".github/workflows/TestRelease.yml")
        }.first { it.isFile }
        workflowFile.readText().replace("\r\n", "\n")
    }

    private val lanzouUploaderText by lazy {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        val scriptFile = generateSequence(File(userDir)) {
            it.parentFile
        }.map {
            File(it, ".github/scripts/lzy_web.py")
        }.first { it.isFile }
        scriptFile.readText().replace("\r\n", "\n")
    }

    @Test
    fun `test release runs for every master push`() {
        assertTrue(workflowText.contains("push:"))
        assertTrue(workflowText.contains("- master"))
        assertTrue(workflowText.contains("workflow_dispatch:"))
        assertTrue(workflowText.contains("commit_sha:"))
        assertTrue(workflowText.contains("ref: ${'$'}{{ inputs.commit_sha || github.sha }}"))
        assertTrue(workflowText.contains("ref: ${'$'}{{ needs.prepare.outputs.commit }}"))
        assertTrue(workflowText.contains("if: ${'$'}{{ github.repository == 'Bundle5936/legado' && !github.event.deleted }}"))
        assertTrue(workflowText.contains("group: github-apk-release"))
        assertTrue(workflowText.contains("cancel-in-progress: false"))
        assertTrue(workflowText.contains("queue: max"))
        assertFalse(workflowText.contains("pull_request:"))
        assertFalse(workflowText.contains("github.event.pull_request"))
        assertFalse(workflowText.contains("github.event.head_commit"))
    }

    @Test
    fun `self release preserves published history and needs no external credentials`() {
        val publish = workflowText.substringAfter("  publish:")
        assertTrue(workflowText.contains("versionCode: ${'$'}{{ steps.set-ver.outputs.versionCode }}"))
        assertTrue(workflowText.contains("git rev-list \"${'$'}{base_commit}..HEAD\" --count --no-merges"))
        assertTrue(workflowText.contains("self-${'$'}{version_code}-${'$'}{COMMIT_SHA:0:12}"))
        assertTrue(workflowText.contains("cp .github/workflows/legado.jks app/legado.jks"))
        assertTrue(publish.contains("pattern: legado.self.*"))
        assertTrue(publish.contains("<!-- legado-version-code:%s -->"))
        assertTrue(publish.contains("SHA256SUMS.txt"))
        assertTrue(publish.contains("extract-latest-update.sh"))
        assertTrue(publish.indexOf("gh release create") < publish.indexOf("gh release upload"))
        assertTrue(publish.indexOf("gh release upload") < publish.indexOf("--draft=false"))
        assertTrue(publish.contains("if [ \"${'$'}release_state\" = published ]; then"))
        assertTrue(publish.contains("if [ \"${'$'}master_sha\" = \"${'$'}COMMIT_SHA\" ]; then latest=true; fi"))
        assertFalse(workflowText.contains("secrets."))
        assertFalse(workflowText.contains("LANZOU"))
        assertFalse(workflowText.contains("lzy_web.py"))
        assertFalse(workflowText.contains("removeArtifacts"))
        assertFalse(workflowText.contains("git tag --force"))
        assertFalse(workflowText.contains("Post to Telegram Channel"))
    }

    @Test
    fun `lanzou uploader propagates login and upload failures`() {
        assertTrue(lanzouUploaderText.contains("with open(file_dir, \"rb\") as upload_stream:"))
        assertTrue(lanzouUploaderText.contains("return 0 if upload(argv[0], argv[1]) else 1"))
        assertTrue(lanzouUploaderText.contains("sys.exit(main(sys.argv[1:]))"))
        assertFalse(lanzouUploaderText.contains("\"name\": '{file_name}'"))
        assertFalse(lanzouUploaderText.contains("retry_tim+"))
        assertTrue(lanzouUploaderText.contains("mydisk.php?item=files&action=index&u="))
        assertTrue(lanzouUploaderText.contains("allow_redirects=False"))
        assertTrue(lanzouUploaderText.contains("accounts.woozooo.com"))
    }
}
