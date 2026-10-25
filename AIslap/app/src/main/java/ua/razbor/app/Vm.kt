package ua.razbor.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.util.Locale

class Vm(app: Application) : AndroidViewModel(app) {
    private val ctx = app
    private val sp = app.getSharedPreferences("razbor", 0)
    private var job: Job? = null
    private var tts: TextToSpeech? = null

    // --- что выбрал ученик ---
    var grade by mutableIntStateOf(sp.getInt("grade", 9))
    var subject by mutableStateOf(sp.getString("subject", "Алгебра")!!)
    var persona by mutableStateOf(Persona.STUDENT)
    var text by mutableStateOf("")
    var preview by mutableStateOf<ImageBitmap?>(null)
    private var imgPath: String? = null

    // --- ответ ---
    var answer by mutableStateOf("")
    var busy by mutableStateOf(false)
    var status by mutableStateOf("")

    // --- настройки ИИ ---
    var mode by mutableStateOf(sp.getString("mode", "offline")!!)
    var gpu by mutableStateOf(sp.getBoolean("gpu", false))
    var apiKey by mutableStateOf(sp.getString("key", "")!!)
    var gModel by mutableStateOf(sp.getString("gm", "gemini-2.5-flash")!!)
    var modelUrl by mutableStateOf(sp.getString("url", DEFAULT_URL)!!)
    var modelReady by mutableStateOf(false)
    var dl by mutableFloatStateOf(-1f)
    var dlText by mutableStateOf("")

    // --- данные ---
    val history = mutableStateListOf<Entry>()
    val cards = mutableStateListOf<Card>()
    var streak by mutableIntStateOf(sp.getInt("streak", 0))

    private val modelFile get() = File(ctx.filesDir, "models/" + modelUrl.substringAfterLast('/'))

    init {
        if (subject !in subjectsFor(grade)) subject = subjectsFor(grade).first()
        modelReady = modelFile.exists()
        runCatching {
            val a = JSONArray(sp.getString("hist", "[]"))
            for (i in 0 until a.length()) a.getJSONObject(i).let {
                history.add(Entry(it.getLong("id"), it.getInt("g"), it.getString("s"), it.getString("a"), it.getString("t")))
            }
            val c = JSONArray(sp.getString("cards", "[]"))
            for (i in 0 until c.length()) c.getJSONObject(i).let {
                cards.add(Card(it.getLong("id"), it.getString("s"), it.getString("q"), it.getString("a")))
            }
        }
        tts = TextToSpeech(ctx) { if (it == TextToSpeech.SUCCESS) tts?.language = Locale("uk", "UA") }
    }

    override fun onCleared() { tts?.shutdown() }

    fun pickGrade(g: Int) {
        grade = g
        if (subject !in subjectsFor(g)) subject = subjectsFor(g).first()
        saveCfg()
    }

    fun saveCfg() {
        sp.edit().putInt("grade", grade).putString("subject", subject).putString("mode", mode)
            .putBoolean("gpu", gpu).putString("key", apiKey).putString("gm", gModel).putString("url", modelUrl).apply()
        modelReady = modelFile.exists()
    }

    // ---------- фото ----------
    fun setImage(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val k = 1280f / maxOf(info.size.width, info.size.height)
                    if (k < 1f) d.setTargetSize((info.size.width * k).toInt(), (info.size.height * k).toInt())
                }
                val f = File(ctx.cacheDir, "in.jpg")
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                imgPath = f.path
                preview = bmp.asImageBitmap()
            } catch (e: Throwable) {
                status = "Не удалось открыть фото"
            }
        }
    }

    fun clearImage() { preview = null; imgPath = null }

    // ---------- запрос к ИИ ----------
    private suspend fun run(prompt: String, img: String?): String {
        return if (mode == "offline") {
            if (!modelReady) error("Сначала скачай модель во вкладке «ИИ»")
            status = "Загружаю модель в память (в первый раз до минуты)…"
            Ai.prepare(ctx, modelFile.path, gpu)
            status = "Думаю…"
            val sb = StringBuilder()
            Ai.stream(prompt, img).collect { sb.append(it); answer = sb.toString(); status = "" }
            sb.toString()
        } else {
            if (apiKey.isBlank()) error("Укажи ключ Gemini во вкладке «ИИ»")
            status = "Думаю…"
            Ai.gemini(apiKey, gModel, prompt, img).also { answer = it }
        }
    }

    fun ask(a: Act) {
        if (busy) return
        busy = true; answer = ""; status = ""
        job = viewModelScope.launch {
            try {
                val img = if (preview != null) imgPath else null
                val out = run(Ai.prompt(grade, subject, persona, a, text, img != null), img)
                if (out.isNotBlank()) { addHistory(a, out); bumpStreak() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                answer += (if (answer.isEmpty()) "" else "\n\n") + "Ошибка: ${e.message ?: e}"
            } finally { busy = false; status = "" }
        }
    }

    fun stop() { job?.cancel() }

    fun makeCards() {
        if (busy || answer.isBlank()) return
        busy = true; status = "Готовлю карточки…"
        job = viewModelScope.launch {
            try {
                val saved = answer
                val p = "Сделай 5 карточек для запоминания по этому тексту (предмет: $subject, $grade класс). " +
                    "Формат строго: вопрос :: ответ, по одной карточке на строку, без нумерации и пояснений.\n\n$saved"
                val out = run(p, null)
                answer = saved
                val fresh = out.lines().filter { "::" in it }.map {
                    val (q, a) = it.split("::", limit = 2)
                    Card(System.nanoTime(), subject, q.trim().trimStart('-', '*', '•', ' '), a.trim())
                }
                cards.addAll(0, fresh)
                saveCards()
                status = if (fresh.isEmpty()) "Модель ответила в другом формате, попробуй еще раз"
                else "Добавлено карточек: ${fresh.size}. Они во вкладке «Карточки»"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                status = "Ошибка: ${e.message ?: e}"
            } finally { busy = false }
        }
    }

    fun speak() {
        val t = tts ?: return
        if (t.isSpeaking) t.stop() else t.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "a")
    }

    // ---------- история, карточки, серия дней ----------
    private fun addHistory(a: Act, out: String) {
        history.add(0, Entry(System.currentTimeMillis(), grade, subject, a.title, out))
        while (history.size > 50) history.removeAt(history.size - 1)
        sp.edit().putString("hist", JSONArray().apply {
            history.forEach { put(JSONObject().put("id", it.id).put("g", it.grade).put("s", it.subject).put("a", it.act).put("t", it.text)) }
        }.toString()).apply()
    }

    fun delEntry(e: Entry) { history.remove(e); addHistory0() }
    private fun addHistory0() {
        sp.edit().putString("hist", JSONArray().apply {
            history.forEach { put(JSONObject().put("id", it.id).put("g", it.grade).put("s", it.subject).put("a", it.act).put("t", it.text)) }
        }.toString()).apply()
    }

    fun delCard(c: Card) { cards.remove(c); saveCards() }
    private fun saveCards() {
        sp.edit().putString("cards", JSONArray().apply {
            cards.forEach { put(JSONObject().put("id", it.id).put("s", it.subject).put("q", it.q).put("a", it.a)) }
        }.toString()).apply()
    }

    private fun bumpStreak() {
        val today = LocalDate.now().toEpochDay()
        val last = sp.getLong("last", 0)
        if (today == last) return
        streak = if (today == last + 1) streak + 1 else 1
        sp.edit().putLong("last", today).putInt("streak", streak).apply()
    }

    // ---------- модель ----------
    fun download() {
        if (dl >= 0) return
        dl = 0f; dlText = "Подключаюсь…"
        viewModelScope.launch {
            try {
                Ai.download(modelUrl, modelFile) { d, t ->
                    dl = if (t > 0) d.toFloat() / t else 0f
                    dlText = "%.0f из %.0f МБ".format(d / 1e6, t / 1e6)
                }
                modelReady = modelFile.exists()
                dlText = "Готово. Теперь ИИ работает без интернета."
            } catch (e: Throwable) {
                dlText = "Ошибка: ${e.message}. Нажми «Скачать» еще раз, загрузка продолжится."
            } finally { dl = -1f }
        }
    }

    fun deleteModel() { modelFile.delete(); File(modelFile.path + ".part").delete(); modelReady = false; dlText = "" }
}
