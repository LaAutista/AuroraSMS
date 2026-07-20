// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.aurorasms.app.R
import org.aurorasms.core.state.MessageSignature

@Composable
internal fun GlobalMessageSignatureDialog(
    current: MessageSignature?,
    onSave: (MessageSignature?) -> Boolean,
    onDismiss: () -> Unit,
) {
    var text by remember(current) { mutableStateOf(current?.value.orEmpty()) }
    val parsed = MessageSignature.fromUserInput(text)
    val valid = text.isBlank() || parsed != null
    AlertDialog(
        modifier = Modifier.testTag(GLOBAL_SIGNATURE_DIALOG_TEST_TAG),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.message_signature_title)) },
        text = {
            SignatureEditorContent(
                text = text,
                enabled = true,
                onTextChanged = { if (it.length <= MessageSignature.MAX_CHARACTERS) text = it },
                supportingText = stringResource(R.string.message_signature_explanation),
            )
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag(SAVE_GLOBAL_SIGNATURE_TEST_TAG),
                enabled = valid,
                onClick = { if (onSave(parsed)) onDismiss() },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
internal fun ConversationMessageSignatureDialog(
    inherited: MessageSignature?,
    current: ConversationSignatureOverride,
    onSave: (ConversationSignatureOverride) -> Boolean,
    onDismiss: () -> Unit,
) {
    var mode by remember(current) {
        mutableStateOf(
            when (current) {
                ConversationSignatureOverride.Inherit -> ConversationSignatureEditorMode.INHERIT
                ConversationSignatureOverride.Disabled -> ConversationSignatureEditorMode.DISABLED
                is ConversationSignatureOverride.Custom -> ConversationSignatureEditorMode.CUSTOM
            },
        )
    }
    var text by remember(current) {
        mutableStateOf((current as? ConversationSignatureOverride.Custom)?.signature?.value.orEmpty())
    }
    val parsed = MessageSignature.fromUserInput(text)
    val selection = when (mode) {
        ConversationSignatureEditorMode.INHERIT -> ConversationSignatureOverride.Inherit
        ConversationSignatureEditorMode.DISABLED -> ConversationSignatureOverride.Disabled
        ConversationSignatureEditorMode.CUSTOM -> parsed?.let(ConversationSignatureOverride::Custom)
    }
    AlertDialog(
        modifier = Modifier.testTag(CONVERSATION_SIGNATURE_DIALOG_TEST_TAG),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.conversation_signature_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SignatureModeRow(
                    selected = mode == ConversationSignatureEditorMode.INHERIT,
                    label = if (inherited == null) {
                        stringResource(R.string.use_global_signature_off)
                    } else {
                        stringResource(R.string.use_global_signature)
                    },
                    onClick = { mode = ConversationSignatureEditorMode.INHERIT },
                )
                SignatureModeRow(
                    selected = mode == ConversationSignatureEditorMode.DISABLED,
                    label = stringResource(R.string.disable_signature_for_conversation),
                    onClick = { mode = ConversationSignatureEditorMode.DISABLED },
                )
                SignatureModeRow(
                    selected = mode == ConversationSignatureEditorMode.CUSTOM,
                    label = stringResource(R.string.custom_conversation_signature),
                    onClick = { mode = ConversationSignatureEditorMode.CUSTOM },
                )
                SignatureEditorContent(
                    text = text,
                    enabled = mode == ConversationSignatureEditorMode.CUSTOM,
                    onTextChanged = {
                        if (it.length <= MessageSignature.MAX_CHARACTERS) text = it
                    },
                    supportingText = stringResource(R.string.message_signature_explanation),
                )
            }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag(SAVE_CONVERSATION_SIGNATURE_TEST_TAG),
                enabled = selection != null,
                onClick = { selection?.let { if (onSave(it)) onDismiss() } },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun SignatureEditorContent(
    text: String,
    enabled: Boolean,
    onTextChanged: (String) -> Unit,
    supportingText: String,
) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag(SIGNATURE_TEXT_FIELD_TEST_TAG),
        value = text,
        onValueChange = onTextChanged,
        enabled = enabled,
        label = { Text(stringResource(R.string.signature_text)) },
        supportingText = {
            Text("$supportingText\n${text.length}/${MessageSignature.MAX_CHARACTERS}")
        },
        minLines = 2,
        maxLines = MessageSignature.MAX_LINES,
    )
}

@Composable
private fun SignatureModeRow(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        TextButton(onClick = onClick) { Text(label) }
    }
}

private enum class ConversationSignatureEditorMode { INHERIT, DISABLED, CUSTOM }

internal const val GLOBAL_SIGNATURE_DIALOG_TEST_TAG = "aurora-global-signature-dialog"
internal const val CONVERSATION_SIGNATURE_DIALOG_TEST_TAG = "aurora-conversation-signature-dialog"
internal const val SIGNATURE_TEXT_FIELD_TEST_TAG = "aurora-signature-text-field"
internal const val SAVE_GLOBAL_SIGNATURE_TEST_TAG = "aurora-save-global-signature"
internal const val SAVE_CONVERSATION_SIGNATURE_TEST_TAG = "aurora-save-conversation-signature"
