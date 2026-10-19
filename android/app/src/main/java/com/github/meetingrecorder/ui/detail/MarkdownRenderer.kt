package com.github.meetingrecorder.ui.detail

import android.content.Context
import android.widget.TextView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin

/** Native text spans: no WebView, JavaScript or remote image loading. */
class MarkdownRenderer(context: Context) {
    private val markwon = Markwon.builder(context)
        .usePlugin(TablePlugin.create(context))
        .usePlugin(TaskListPlugin.create(context))
        .build()
    fun render(source: String): CharSequence = markwon.toMarkdown(source)
    fun bind(view: TextView, source: String) {
        markwon.setMarkdown(view, source)
    }
}
