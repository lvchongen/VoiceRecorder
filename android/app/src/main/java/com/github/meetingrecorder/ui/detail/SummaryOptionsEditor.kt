package com.github.meetingrecorder.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.meetingrecorder.data.MeetingTemplate
import com.github.meetingrecorder.data.SummaryDetail
import com.github.meetingrecorder.data.SummaryOptions

@Composable
fun SummaryOptionsEditor(options: SummaryOptions, onChange: (SummaryOptions) -> Unit, showLanguage: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SummaryChoice("会议类型", options.template, MeetingTemplate.entries.map { it to it.label }) { onChange(options.copy(template = it)) }
        SummaryChoice("详细程度", options.detail, SummaryDetail.entries.map { it to it.label }) { onChange(options.copy(detail = it)) }
        if (showLanguage) {
            SummaryChoice("摘要语言", options.language, listOf("Bilingual" to "中英双语", "Chinese" to "中文", "Original" to "原语言")) { onChange(options.copy(language = it)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> SummaryChoice(label: String, value: T, choices: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = choices.find { it.first == value }?.second.orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { (key, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = {
                    expanded = false
                    onSelect(key)
                })
            }
        }
    }
}
