package ua.razbor.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Ink = Color(0xFF14213D)
private val Marker = Color(0xFFFFE45C)

@Composable
fun RazborTheme(content: @Composable () -> Unit) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val cs = if (dark) darkColorScheme(
        primary = Color(0xFF8FA2FF), onPrimary = Color(0xFF0F1424), background = Color(0xFF0F1424),
        surface = Color(0xFF171E36), onBackground = Color(0xFFE8ECF8), onSurface = Color(0xFFE8ECF8),
        secondaryContainer = Color(0xFF2B3558), onSecondaryContainer = Color(0xFFE8ECF8), surfaceVariant = Color(0xFF171E36)
    ) else lightColorScheme(
        primary = Color(0xFF2438D6), onPrimary = Color.White, background = Color(0xFFEEF2F8),
        surface = Color.White, onBackground = Ink, onSurface = Ink,
        secondaryContainer = Color(0xFFD9DFFB), onSecondaryContainer = Ink, surfaceVariant = Color.White
    )
    MaterialTheme(colorScheme = cs, content = content)
}

/** Фирменный фон: тетрадь в клетку. */
private fun Modifier.paperGrid(line: Color) = drawBehind {
    val s = 24.dp.toPx()
    var x = 0f
    while (x < size.width) { drawLine(line, Offset(x, 0f), Offset(x, size.height), 1f); x += s }
    var y = 0f
    while (y < size.height) { drawLine(line, Offset(0f, y), Offset(size.width, y), 1f); y += s }
}

@Composable
fun App(vm: Vm) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val grid = if (dark) Color(0xFF1A2240) else Color(0xFFD3DCEC)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar {
                listOf("Решить" to "✏️", "История" to "🕘", "Карточки" to "🗂", "ИИ" to "🤖").forEachIndexed { i, (t, e) ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text(e) }, label = { Text(t) })
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().paperGrid(grid).statusBarsPadding().imePadding()) {
            when (tab) { 0 -> SolveTab(vm); 1 -> HistoryTab(vm); 2 -> CardsTab(vm); else -> AiTab(vm) }
        }
    }
}

@Composable
private fun styled(t: String): AnnotatedString {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val mark = Marker.copy(alpha = if (dark) .25f else .6f)
    val ok = if (dark) Color(0xFF6EE0A8) else Color(0xFF0B7A4B)
    val ans = Regex("^(ответ|відповідь|answer)", RegexOption.IGNORE_CASE)
    val formula = Regex("[=²³√∫≈≤≥^]|\\d\\s*[+\\-−×÷*/]\\s*\\d")
    return buildAnnotatedString {
        t.lines().forEach { l ->
            val s = l.trim()
            when {
                ans.containsMatchIn(s) -> withStyle(SpanStyle(color = ok, fontWeight = FontWeight.Bold)) { append(l) }
                formula.containsMatchIn(s) -> withStyle(SpanStyle(background = mark, fontFamily = FontFamily.Monospace)) { append(l) }
                else -> append(l)
            }
            append("\n")
        }
    }
}

@Composable
private fun H(t: String) = Text(t, fontWeight = FontWeight.Bold, fontSize = 15.sp)

@Composable
private fun Muted(t: String, size: Int = 13) =
    Text(t, fontSize = size.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = .65f))

@Composable
private fun Empty(t: String) = Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
    Text(t, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onBackground.copy(alpha = .7f))
}

// ======================= РЕШИТЬ =======================
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SolveTab(vm: Vm) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val camFile = remember { File(ctx.cacheDir, "cam.jpg") }
    val cam = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) vm.setImage(Uri.fromFile(camFile))
    }
    val gal = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(vm::setImage) }
    val can = !vm.busy && (vm.text.isNotBlank() || vm.preview != null)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Разбор", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                Muted("помощь с учебой, бесплатно")
            }
            Surface(shape = RoundedCornerShape(50), color = Marker, border = BorderStroke(1.5.dp, Ink)) {
                Text("🔥 ${vm.streak}", Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = Ink, fontWeight = FontWeight.Bold)
            }
        }

        H("Класс")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (5..11).forEach { g -> FilterChip(selected = vm.grade == g, onClick = { vm.pickGrade(g) }, label = { Text("$g класс") }) }
        }

        H("Предмет")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            subjectsFor(vm.grade).forEach { s ->
                FilterChip(selected = vm.subject == s, onClick = { vm.subject = s; vm.saveCfg() }, label = { Text(s) })
            }
        }

        H("Задание")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                camFile.parentFile?.mkdirs(); camFile.delete(); camFile.createNewFile()
                cam.launch(FileProvider.getUriForFile(ctx, "${ctx.packageName}.fp", camFile))
            }, Modifier.weight(1f)) { Text("Камера") }
            OutlinedButton(onClick = { gal.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                Modifier.weight(1f)) { Text("Галерея") }
        }
        vm.preview?.let {
            Image(it, "Фото задания", Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Fit)
            TextButton(onClick = { vm.clearImage() }) { Text("Убрать фото") }
        }
        OutlinedTextField(
            value = vm.text, onValueChange = { vm.text = it }, modifier = Modifier.fillMaxWidth(), minLines = 2,
            placeholder = { Text("Или напиши условие. Для проверки ошибки добавь свое решение.") }
        )

        if (vm.mode == "offline" && !vm.modelReady) {
            Card(colors = CardDefaults.cardColors(containerColor = Marker)) {
                Text("Сначала скачай встроенный ИИ во вкладке «ИИ». Это один раз, потом интернет не нужен.",
                    Modifier.padding(12.dp), color = Ink)
            }
        }

        H("Что сделать")
        Act.entries.chunked(2).forEach { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                r.forEach { a ->
                    Button(onClick = { vm.ask(a) }, enabled = can, shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).heightIn(min = 64.dp)) { Text(a.title, textAlign = TextAlign.Center) }
                }
            }
        }

        H("Стиль ответа")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Persona.entries.forEachIndexed { i, p ->
                SegmentedButton(selected = vm.persona == p, onClick = { vm.persona = p },
                    shape = SegmentedButtonDefaults.itemShape(i, Persona.entries.size), icon = {}) {
                    Text(p.title, fontSize = 12.sp, maxLines = 1, softWrap = false)
                }
            }
        }
        Muted(vm.persona.hint)

        if (vm.busy || vm.status.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (vm.busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Text(vm.status, Modifier.weight(1f), fontSize = 14.sp)
                if (vm.busy) TextButton(onClick = { vm.stop() }) { Text("Стоп") }
            }
        }

        if (vm.answer.isNotEmpty()) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(2.dp, MaterialTheme.colorScheme.onBackground)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(styled(vm.answer), fontSize = 16.sp, lineHeight = 24.sp)
                    if (!vm.busy) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { clip.setText(AnnotatedString(vm.answer)) }) { Text("Копировать") }
                        OutlinedButton(onClick = { vm.speak() }) { Text("Озвучить") }
                        OutlinedButton(onClick = { vm.makeCards() }) { Text("Карточки") }
                    }
                }
            }
        }
        Muted("Ответ помогает разобраться в теме. Сверяй его с учебником: ИИ тоже ошибается.", 12)
        Spacer(Modifier.height(8.dp))
    }
}

// ======================= ИСТОРИЯ =======================
@Composable
fun HistoryTab(vm: Vm) {
    val clip = LocalClipboardManager.current
    if (vm.history.isEmpty()) { Empty("Здесь появятся твои разборы.\nРеши первое задание во вкладке «Решить»."); return }
    val fmt = remember { SimpleDateFormat("d MMM, HH:mm", Locale("ru")) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("История", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold) }
        items(vm.history, key = { it.id }) { e ->
            var open by remember { mutableStateOf(false) }
            Card(Modifier.fillMaxWidth().clickable { open = !open },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.onBackground)) {
                Column(Modifier.padding(14.dp)) {
                    Text("${e.subject} · ${e.grade} класс", fontWeight = FontWeight.Bold)
                    Muted("${e.act} · ${fmt.format(Date(e.id))}", 12)
                    Spacer(Modifier.height(6.dp))
                    if (open) {
                        Text(styled(e.text), lineHeight = 22.sp)
                        Row {
                            TextButton(onClick = { clip.setText(AnnotatedString(e.text)) }) { Text("Копировать") }
                            TextButton(onClick = { vm.delEntry(e) }) { Text("Удалить") }
                        }
                    } else Text(e.text, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                }
            }
        }
    }
}

// ======================= КАРТОЧКИ =======================
@Composable
fun CardsTab(vm: Vm) {
    if (vm.cards.isEmpty()) { Empty("Карточек пока нет.\nПосле разбора нажми «Карточки», и ИИ сделает вопросы для запоминания."); return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Карточки", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold); Muted("Нажми на карточку, чтобы увидеть ответ") }
        items(vm.cards, key = { it.id }) { c ->
            var back by remember { mutableStateOf(false) }
            Card(Modifier.fillMaxWidth().heightIn(min = 90.dp).clickable { back = !back },
                colors = CardDefaults.cardColors(containerColor = if (back) Marker else MaterialTheme.colorScheme.surface),
                border = BorderStroke(2.dp, if (back) Ink else MaterialTheme.colorScheme.onBackground)) {
                Column(Modifier.padding(14.dp)) {
                    Text(c.subject, fontSize = 12.sp, color = if (back) Ink else MaterialTheme.colorScheme.primary)
                    Text(if (back) c.a else c.q, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = if (back) Ink else MaterialTheme.colorScheme.onSurface)
                    if (back) TextButton(onClick = { vm.delCard(c) }) { Text("Удалить", color = Ink) }
                }
            }
        }
    }
}

// ======================= ИИ / НАСТРОЙКИ =======================
@Composable
fun AiTab(vm: Vm) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Встроенный ИИ", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = vm.mode == "offline", onClick = { vm.mode = "offline"; vm.saveCfg() }, label = { Text("Без интернета") })
            FilterChip(selected = vm.mode == "online", onClick = { vm.mode = "online"; vm.saveCfg() }, label = { Text("Онлайн (Gemini)") })
        }

        if (vm.mode == "offline") {
            Muted("Gemma 4 от Google работает прямо на телефоне. Нужен телефон с 6 ГБ памяти и более, около 3 ГБ свободного места и один раз Wi-Fi для загрузки. Читает и фото.")
            Text(if (vm.modelReady) "Модель на телефоне: готова ✓" else "Модель не скачана", fontWeight = FontWeight.Bold)
            if (vm.dl >= 0f) LinearProgressIndicator(progress = { vm.dl }, Modifier.fillMaxWidth())
            if (vm.dlText.isNotEmpty()) Muted(vm.dlText)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.download() }, enabled = vm.dl < 0f && !vm.modelReady, modifier = Modifier.weight(1f)) {
                    Text(if (vm.modelReady) "Скачано" else "Скачать (≈2,6 ГБ)")
                }
                if (vm.modelReady) OutlinedButton(onClick = { vm.deleteModel() }, Modifier.weight(1f)) { Text("Удалить") }
            }
            Muted("Во время загрузки держи приложение открытым. Если связь оборвется, нажми кнопку еще раз: загрузка продолжится.", 12)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Ускорение через GPU"); Muted("Быстрее, но на части телефонов не запускается", 12) }
                Switch(checked = vm.gpu, onCheckedChange = { vm.gpu = it; vm.saveCfg() })
            }
            OutlinedTextField(value = vm.modelUrl, onValueChange = { vm.modelUrl = it; vm.saveCfg() }, label = { Text("Адрес файла модели (.litertlm)") },
                modifier = Modifier.fillMaxWidth())
        } else {
            Muted("Бесплатный ключ берется в Google AI Studio (aistudio.google.com). Запросы уходят в интернет, ключ хранится только на телефоне.")
            OutlinedTextField(value = vm.apiKey, onValueChange = { vm.apiKey = it; vm.saveCfg() }, label = { Text("Ключ Gemini") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = vm.gModel, onValueChange = { vm.gModel = it; vm.saveCfg() }, label = { Text("Модель") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
        }
    }
}
