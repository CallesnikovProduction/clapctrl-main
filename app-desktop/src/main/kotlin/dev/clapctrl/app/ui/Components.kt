package dev.clapctrl.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(Surface).border(1.dp, Border, RoundedCornerShape(12.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}
@Composable fun Eyebrow(text: String) { Text(text.uppercase(), color = Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp) }
@Composable fun Hint(text: String, color: Color = Muted) { Text(text, color = color, fontSize = 12.sp, lineHeight = 18.sp) }
@Composable fun SectionTitle(title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
        Hint(description)
    }
}
@Composable fun Signal(levels: List<Float>, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(62.dp)) {
        val spacing = size.width / levels.size.coerceAtLeast(1)
        drawLine(Border, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1f)
        levels.forEachIndexed { index, value ->
            val height = (value * size.height).coerceAtLeast(2f)
            drawLine(if (value > 0.005f) Accent else Border,
                Offset(index * spacing + spacing / 2, (size.height - height) / 2),
                Offset(index * spacing + spacing / 2, (size.height + height) / 2),
                (spacing * 0.45f).coerceAtLeast(1f))
        }
    }
}
@Composable fun Waveform(samples: FloatArray) {
    Canvas(Modifier.fillMaxWidth().height(100.dp).background(Background, RoundedCornerShape(8.dp))) {
        val stride = (samples.size / size.width.toInt().coerceAtLeast(1)).coerceAtLeast(1)
        var previous = Offset(0f, size.height / 2)
        for (i in samples.indices step stride) {
            val point = Offset(i.toFloat() / samples.size.coerceAtLeast(1) * size.width, size.height / 2 - samples[i] * size.height * 0.45f)
            drawLine(Accent, previous, point, 1.2f)
            previous = point
        }
    }
}
@Composable fun <T> Choice(value: T, options: List<T>, label: (T) -> String, enabled: Boolean = true, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(7.dp), border = BorderStroke(1.dp, Border), contentPadding = PaddingValues(12.dp)) {
            Text(label(value), modifier = Modifier.weight(1f), fontSize = 13.sp)
            Text("⌄", color = Muted)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }, modifier = Modifier.widthIn(max = 480.dp)) {
            options.forEach { option -> DropdownMenuItem(onClick = { onSelect(option); expanded = false }) { Text(label(option), fontSize = 13.sp) } }
        }
    }
}
