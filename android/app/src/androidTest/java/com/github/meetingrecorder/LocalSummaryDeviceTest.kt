package com.github.meetingrecorder

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.meetingrecorder.data.LlamaTextEngine
import com.github.meetingrecorder.data.LocalSummaryModel
import com.github.meetingrecorder.data.LocalSummaryModelStore
import com.github.meetingrecorder.data.SummaryOptions
import com.github.meetingrecorder.data.buildSummaryPrompt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LocalSummaryDeviceTest {
    @Test fun chineseSummary(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("localSummarySmoke") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = LocalSummaryModel.selected(args.getString("summaryModel") ?: "qwen35-2b")
        val report = File(context.cacheDir, "local-summary-smoke.txt")
        report.writeText("model=${model.id}\n")
        val store = LocalSummaryModelStore(File(context.noBackupFilesDir, "local-summary-models"))
        store.withModel(model) { file ->
            LlamaTextEngine(file, model.format).use { engine ->
                val transcript = "[00:00:01] A：今天讨论发布计划。由于支付测试没有通过，决定取消本周五发布。\n[00:00:15] B：我叫李明，我负责周四下午三点前修复支付故障，完成后通知王芳复测。\n[00:00:30] A：新的上线日期尚未确定，不能写成下周一。"
                val prompt = buildSummaryPrompt(transcript, SummaryOptions(language = "Chinese"))
                val start = System.currentTimeMillis()
                val result = engine.complete(prompt, 1024) { report.appendText(it + "\n") }
                report.appendText("elapsedMs=${System.currentTimeMillis() - start}\n$result\n")
                assertTrue("Expected Chinese Markdown summary", result.count { it in '\u4e00'..'\u9fff' } > 20)
                assertTrue("Expected concrete meeting content", result.contains("支付"))
                // Second request verifies context reset and valid lifecycle in the same loaded model.
                val title = engine.complete("请给出下面内容的简短中文标题，只返回标题：\n$result", 128) {}
                assertTrue(title.isNotBlank())
                report.appendText("title=$title\n")
            }
        }
    }
}
