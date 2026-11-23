package io.kirora.demo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.kirora.demo.DemoStore

@Composable
fun EventsScreen(store: DemoStore, modifier: Modifier = Modifier) {
    val snapshot by store.snapshot.collectAsState()
    val sent by store.sent.collectAsState()
    var name by rememberSaveable { mutableStateOf("Order Completed") }
    var props by rememberSaveable { mutableStateOf("total=125.00\ncurrency=NGN") }

    if (!snapshot.connected) {
        EmptyState("Not connected", "Connect your workspace on the first tab.", modifier)
        return
    }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Section("Track an event") {
                Text(
                    "Events reach your workspace in batches and can trigger journeys and segments there.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, label = { Text("Event name") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = props, onValueChange = { props = it }, label = { Text("Properties (key=value per line)") },
                    minLines = 3, modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { store.track(name, parseProperties(props)) }) { Text("Send event") }
                    OutlinedButton(onClick = { store.trackScreen(name.ifBlank { "Home" }) }) { Text("Send as screen view") }
                }
            }
        }
        item {
            Section("Delivery") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${snapshot.queued} waiting to send", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (snapshot.identified) "Sent automatically every 30 s or at 20 events"
                            else "Held until you sign in",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(onClick = store::flush, enabled = snapshot.identified) { Text("Send now") }
                }
            }
        }
        if (sent.isNotEmpty()) {
            item { Text("Recent", style = MaterialTheme.typography.titleMedium) }
            items(sent) {
                Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text("${it.name}  ·  ${formatTime(it.at)}", style = MaterialTheme.typography.bodyMedium)
                    if (it.properties.isNotEmpty()) {
                        Text(
                            it.properties.entries.joinToString("  ") { (k, v) -> "$k=$v" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** `key=value` lines; numbers and booleans keep their type so they segment correctly server-side. */
private fun parseProperties(text: String): Map<String, Any?> = text.lines()
    .mapNotNull { line ->
        val index = line.indexOf('=')
        if (index <= 0) null else line.substring(0, index).trim() to line.substring(index + 1).trim()
    }
    .associate { (k, v) ->
        k to (v.toLongOrNull() ?: v.toDoubleOrNull() ?: v.toBooleanStrictOrNull() ?: v)
    }
