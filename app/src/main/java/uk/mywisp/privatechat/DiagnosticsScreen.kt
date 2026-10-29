package uk.mywisp.privatechat

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun DiagnosticsScreen(journal: DiagnosticJournal, close: () -> Unit) {
    val entries by journal.state.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf("ALL") }
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Диагностика", style = MaterialTheme.typography.headlineMedium)
        Text("Локальный журнал без паролей, токенов, ключей и текста сообщений. Последние 400 записей.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("ALL" to "Все", "INFO" to "Info", "WARNING" to "Warning", "ERROR" to "Error").forEach { (value, label) ->
                FilterChip(selected = filter == value, onClick = { filter = value }, label = { Text(label) })
            }
        }
        Row {
            TextButton(onClick = close) { Text("Назад") }
            TextButton(onClick = journal::clear) { Text("Очистить") }
            TextButton(onClick = {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, "Диагностика мессенджера")
                    putExtra(Intent.EXTRA_TEXT, journal.export())
                }, "Экспорт журнала"))
            }) { Text("Экспорт") }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(entries.filter { filter == "ALL" || it.level == filter }.asReversed()) { entry ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${entry.level} · ${SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date(entry.time))}",
                            color = if (entry.level == "ERROR") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium)
                        SelectionContainer { Text(entry.code + if (entry.detail.isNotBlank()) "\n" + entry.detail else "", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}
