@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package uk.mywisp.privatechat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal fun LazyListScope.appearanceItems(prefs: AppearancePreferences, a: Appearance, section: String = "all") {
    if (section != "layout") {
    item {
        Text("Оформление", style = MaterialTheme.typography.headlineMedium)
        Text("Ваши цвета, ваш ритм", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    item {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("system" to "Авто", "light" to "Светлая", "dark" to "Тёмная").forEach { (value, label) ->
                FilterChip(selected = a.theme == value, onClick = { prefs.update(theme = value) },
                    label = { Text(label) }, modifier = Modifier.weight(1f))
            }
        }
    }
    item { Text("Цвет приложения", style = MaterialTheme.typography.titleMedium) }
    listOf(listOf("dynamic" to "Из обоев", "lavender" to "Лаванда"),
        listOf("ocean" to "Океан", "forest" to "Лес"),
        listOf("rose" to "Роза", "peach" to "Персик")).forEach { row ->
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (value, label) ->
                    val swatch = if (value == "dynamic") MaterialTheme.colorScheme.primary else Color(seedColor(a.copy(accent = value)))
                    FilterChip(selected = a.accent == value, onClick = { prefs.update(accent = value) },
                        modifier = Modifier.weight(1f), label = { Text(label) }, leadingIcon = {
                            Box(Modifier.size(20.dp).background(swatch, CircleShape), contentAlignment = Alignment.Center) {
                                if (a.accent == value) Icon(Icons.Default.Check, null, Modifier.size(14.dp), tint = Color.White)
                            }
                        })
                }
            }
        }
    }
    item {
        FilterChip(selected = a.accent == "custom", onClick = { prefs.update(accent = "custom") }, label = { Text("Свой цвет") })
        if (a.accent == "custom") {
            Box(Modifier.fillMaxWidth().height(8.dp).background(Brush.horizontalGradient(listOf(
                Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)), CircleShape))
            Slider(value = a.hue, onValueChange = { prefs.set(a.copy(hue = it)) }, valueRange = 0f..360f,
                modifier = Modifier.semantics { contentDescription = "Оттенок приложения" })
        }
    }
    item {
        Text("Контраст", style = MaterialTheme.typography.titleMedium)
        Text(if (a.contrast == 0f) "Стандартный" else "Повышенный · ${(a.contrast * 100).toInt()}%",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = a.contrast, onValueChange = { prefs.set(a.copy(contrast = it)) }, valueRange = 0f..1f,
            modifier = Modifier.semantics { contentDescription = "Контраст цветов" })
    }
    item {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Чёрный фон", style = MaterialTheme.typography.titleMedium)
                Text("Для тёмной темы", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = a.amoled, onCheckedChange = { prefs.set(a.copy(amoled = it)) },
                modifier = Modifier.semantics { contentDescription = "Чёрный фон" })
        }
    }
    }
    item { ChatAppearancePreview(a) }
    if (section != "colors") {
    item {
        Text("Масштаб интерфейса · ${(a.uiScale * 100).toInt()}%", style = MaterialTheme.typography.titleMedium)
        Slider(a.uiScale, { prefs.set(a.copy(uiScale = it)) }, valueRange = .85f..1.2f, modifier = Modifier.semantics { contentDescription = "Масштаб интерфейса" })
    }
    item {
        Text("Размер сообщений · ${a.textSize.toInt()}", style = MaterialTheme.typography.titleMedium)
        Slider(value = a.textSize, onValueChange = { prefs.set(a.copy(textSize = it)) }, valueRange = 14f..22f, steps = 7,
            modifier = Modifier.semantics { contentDescription = "Размер сообщений" })
    }
    item {
        Text("Скругление пузырей", style = MaterialTheme.typography.titleMedium)
        Slider(value = a.bubbleRadius, onValueChange = { prefs.set(a.copy(bubbleRadius = it)) }, valueRange = 4f..28f,
            modifier = Modifier.semantics { contentDescription = "Скругление пузырей" })
    }
    item {
        Text("Фон переписки", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("plain" to "Чистый", "tonal" to "Тон", "gradient" to "Градиент").forEach { (key, label) ->
                FilterChip(selected = a.background == key, onClick = { prefs.set(a.copy(background = key)) }, label = { Text(label) })
            }
        }
    }
    item {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Компактные сообщения", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(checked = a.compact, onCheckedChange = { prefs.set(a.copy(compact = it)) },
                modifier = Modifier.semantics { contentDescription = "Компактные сообщения" })
        }
    }
    }
    item { TextButton(onClick = prefs::reset) { Text("Сбросить оформление") } }
}

@Composable
internal fun chatBackground(): Brush {
    val c = MaterialTheme.colorScheme
    return when (LocalAppearance.current.background) {
        "gradient" -> Brush.verticalGradient(listOf(c.surface, c.secondaryContainer, c.tertiaryContainer))
        "tonal" -> Brush.verticalGradient(listOf(c.surfaceContainerLow, c.surfaceContainerLow))
        else -> Brush.verticalGradient(listOf(c.background, c.background))
    }
}

@Composable
private fun ChatAppearancePreview(a: Appearance) {
    Card(shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.fillMaxWidth().background(chatBackground()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Предпросмотр", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(shape = RoundedCornerShape(a.bubbleRadius.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Text("Как тебе новые цвета?", Modifier.padding(if (a.compact) 8.dp else 12.dp),
                    fontSize = a.textSize.sp, color = MaterialTheme.colorScheme.onSurface)
            }
            Surface(Modifier.align(Alignment.End), shape = RoundedCornerShape(a.bubbleRadius.dp),
                color = MaterialTheme.colorScheme.primaryContainer) {
                Text("Теперь всё по-моему ✨", Modifier.padding(if (a.compact) 8.dp else 12.dp),
                    fontSize = a.textSize.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}
