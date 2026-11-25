package com.eric.todo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eric.todo.sync.CloudSync
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Settings section: sign in once on each device and the lists stay the same. */
@Composable
fun SyncSection() {
    val status by CloudSync.status.collectAsStateWithLifecycle()
    val email by CloudSync.email.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            status is CloudSync.Status.NotConfigured -> Text(
                "Sync isn't switched on in this version yet. It turns on automatically in the next update.",
                style = MaterialTheme.typography.bodyLarge,
            )

            email == null -> {
                var mail by remember { mutableStateOf("") }
                var pass by remember { mutableStateOf("") }
                var show by remember { mutableStateOf(false) }
                Text(
                    "Keep the same list on your phone and tablet. Use the same email and password on both. " +
                        "Changes show up on the other device within seconds.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "First device: Create account. Every other device: Sign in.",
                    style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold,
                )
                OutlinedTextField(
                    value = mail, onValueChange = { mail = it }, label = { Text("Email") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 19.sp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = pass, onValueChange = { pass = it }, label = { Text("Password (6+ characters)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { TextButton(onClick = { show = !show }) { Text(if (show) "Hide" else "Show") } },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 19.sp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            busy = true; message = null
                            scope.launch { message = CloudSync.signIn(mail, pass); busy = false }
                        },
                        enabled = !busy && mail.isNotBlank() && pass.isNotBlank(),
                        modifier = Modifier.weight(1f).height(56.dp),
                    ) { Text("Sign in", fontSize = 18.sp) }
                    OutlinedButton(
                        onClick = {
                            busy = true; message = null
                            scope.launch { message = CloudSync.createAccount(mail, pass); busy = false }
                        },
                        enabled = !busy && mail.isNotBlank() && pass.isNotBlank(),
                        modifier = Modifier.weight(1f).height(56.dp),
                    ) { Text("Create account", fontSize = 16.sp) }
                }
                TextButton(
                    onClick = {
                        busy = true; message = null
                        scope.launch {
                            message = CloudSync.resetPassword(mail) ?: "Password reset email sent to $mail"
                            busy = false
                        }
                    },
                    enabled = !busy && mail.isNotBlank(),
                ) { Text("Forgot password", fontSize = 16.sp) }
            }

            else -> {
                Text("Signed in as $email", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                Text(statusText(status), style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            busy = true; message = null
                            scope.launch { message = CloudSync.syncNow() ?: "Synced"; busy = false }
                        },
                        enabled = !busy && status !is CloudSync.Status.Syncing,
                        modifier = Modifier.weight(1f).height(56.dp),
                    ) { Text("Sync now", fontSize = 18.sp) }
                    OutlinedButton(
                        onClick = { CloudSync.signOut(); message = "Signed out. Your list stays on this device." },
                        modifier = Modifier.weight(1f).height(56.dp),
                    ) { Text("Sign out", fontSize = 17.sp) }
                }
                Text(
                    "Sync is automatic. Sign in with this same email on your other phone or tablet. " +
                        "The first time, both lists are combined. Use Find duplicates to tidy any doubles.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        message?.let { Text(it, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
    }
}

fun statusText(status: CloudSync.Status): String = when (status) {
    is CloudSync.Status.Synced -> "✓ Up to date (" + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(status.at)) + ")"
    is CloudSync.Status.Syncing -> "Syncing…"
    is CloudSync.Status.Offline -> status.message
    is CloudSync.Status.Error -> "Sync problem: ${status.message}"
    is CloudSync.Status.SignedOut -> "Not signed in"
    is CloudSync.Status.NotConfigured -> "Sync not switched on yet"
}
