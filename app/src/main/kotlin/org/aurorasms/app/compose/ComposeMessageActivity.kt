// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.compose

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aurorasms.app.AuroraSmsApplication
import org.aurorasms.app.AuroraSmsTheme
import org.aurorasms.app.R
import java.util.Locale

class ComposeMessageActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        (application as AuroraSmsApplication).container.onMessagingActivityStarted()
    }

    override fun onStop() {
        (application as AuroraSmsApplication).container.onMessagingActivityStopped()
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = parseRequest(intent)
        val appContainer = (application as AuroraSmsApplication).container
        setContent {
            val appearance by appContainer.appearanceController.state.collectAsStateWithLifecycle()
            AuroraSmsTheme(profile = appearance.activeProfile) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = getString(R.string.new_message),
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Spacer(Modifier.height(16.dp))
                        if (request == null) {
                            Text(getString(R.string.invalid_compose_request))
                        } else {
                            OutlinedTextField(
                                modifier = Modifier.fillMaxWidth(),
                                value = request.recipient,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(getString(R.string.recipient)) },
                            )
                            Spacer(Modifier.height(12.dp))
                            var body by remember(request.body) { mutableStateOf(request.body) }
                            OutlinedTextField(
                                modifier = Modifier.fillMaxWidth(),
                                value = body,
                                onValueChange = { body = it.take(MAX_BODY_LENGTH) },
                                label = { Text(getString(R.string.message_body)) },
                                minLines = 4,
                            )
                            Spacer(Modifier.height(16.dp))
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                enabled = false,
                                onClick = {},
                            ) {
                                Text(getString(R.string.send_requires_completed_transport))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun parseRequest(source: Intent): ComposeRequest? {
        if (source.action != Intent.ACTION_SENDTO) return null
        val uri = source.data ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme !in ALLOWED_SCHEMES) return null
        val encodedRecipient = uri.encodedSchemeSpecificPart
            ?.substringBefore('?')
            ?.take(MAX_RECIPIENT_LENGTH)
            ?: return null
        val recipient = Uri.decode(encodedRecipient).trim()
        if (recipient.isEmpty() || recipient.any(Char::isISOControl)) return null
        val body = (
            source.getStringExtra("sms_body")
                ?: source.getStringExtra(Intent.EXTRA_TEXT)
                ?: ""
            ).take(MAX_BODY_LENGTH)
        return ComposeRequest(recipient = recipient, body = body)
    }

    private data class ComposeRequest(
        val recipient: String,
        val body: String,
    )

    private companion object {
        val ALLOWED_SCHEMES = setOf("sms", "smsto", "mms", "mmsto")
        const val MAX_RECIPIENT_LENGTH = 1_024
        const val MAX_BODY_LENGTH = 10_000
    }
}
