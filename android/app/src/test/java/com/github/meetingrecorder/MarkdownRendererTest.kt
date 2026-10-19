package com.github.meetingrecorder

import android.text.Spanned
import com.github.meetingrecorder.ui.detail.MarkdownRenderer
import io.noties.markwon.core.spans.HeadingSpan
import io.noties.markwon.core.spans.StrongEmphasisSpan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = android.app.Application::class)
class MarkdownRendererTest {
    @Test
    fun `notes render headings and bold instead of literal markdown`() {
        val result = MarkdownRenderer(RuntimeEnvironment.getApplication()).render("## 决策 / Decisions\n\n**周五发布** / **Ship Friday**")
        assertFalse(result.toString().contains("##"))
        assertFalse(result.toString().contains("**"))
        assertTrue(result is Spanned)
        result as Spanned
        assertTrue(result.getSpans(0, result.length, HeadingSpan::class.java).isNotEmpty())
        assertTrue(result.getSpans(0, result.length, StrongEmphasisSpan::class.java).isNotEmpty())
    }

    @Test
    fun `TextView receives rendered task list and table spans`() {
        val context = RuntimeEnvironment.getApplication()
        val view = android.widget.TextView(context)
        MarkdownRenderer(context).bind(view, "- [ ] 发送报告 / Send report\n\n| Owner | Task |\n| --- | --- |\n| Alice | Review |")
        val text = view.text as Spanned
        val types = text.getSpans(0, text.length, Any::class.java).map { it.javaClass.simpleName }
        assertTrue(types.any { it.contains("TaskList") })
        assertTrue(types.any { it.contains("Table") })
    }
}
