package com.github.meetingrecorder

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController

/** Host UI tests in both variants without adding a test activity to the release APK. */
class RobolectricComposeRule(
    private val delegate: ComposeTestRule = createEmptyComposeRule(),
) : ComposeTestRule by delegate {
    private var controller: ActivityController<ComponentActivity>? = null

    fun setContent(content: @Composable () -> Unit) {
        check(controller == null)
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller!!.get().setContent(content = content)
    }

    override fun apply(base: Statement, description: Description): Statement = delegate.apply(
        object : Statement() {
            override fun evaluate() {
                try {
                    base.evaluate()
                } finally {
                    controller?.close()
                }
            }
        },
        description,
    )
}
