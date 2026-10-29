package uk.mywisp.privatechat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun RecoverySetupDialog(repo: MessengerRepository, verified: Boolean, close: () -> Unit) {
    var old by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var recovery by remember { mutableStateOf("") }
    var reset by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = close, title = { Text("История на любом телефоне") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Однократный перенос старого аккаунта. После него вход и восстановление истории будут работать по логину и новому паролю.")
            Text("Нужен новый пароль: старый передавался серверу для входа. Новый будет использоваться для защиты ключей только на телефоне.")
            OutlinedTextField(old, { old = it }, label = { Text("Текущий пароль") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            OutlinedTextField(next, { next = it }, label = { Text("Новый пароль") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            OutlinedTextField(repeat, { repeat = it }, label = { Text("Повторите новый пароль") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            if (!verified) {
                Text("Это устройство пока не подтверждено. Если сохранился прежний ключ, введите его для сохранения истории.")
                OutlinedTextField(recovery, { recovery = it }, label = { Text("Прежний ключ, если есть") }, visualTransformation = PasswordVisualTransformation())
                Row {
                    Checkbox(checked = reset, onCheckedChange = { reset = it })
                    Text("Ключей и другого доверенного устройства нет. Создать новую защиту. Уже утраченную старую историю восстановить будет невозможно.", Modifier.padding(top = 8.dp))
                }
            }
        }
    }, confirmButton = { TextButton(enabled = old.isNotBlank() && next.length >= 12 && next == repeat && next != old && (verified || recovery.isNotBlank() || reset), onClick = {
        repo.migratePasswordRecovery(old, next, recovery, reset); old = ""; next = ""; repeat = ""; recovery = ""; close()
    }) { Text("Настроить") } }, dismissButton = { TextButton(onClick = close) { Text("Отмена") } })
}
