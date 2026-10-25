package ua.razbor.app

import android.content.Context
import android.util.Base64
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object Ai {
    // Все вызовы нейросети идут через один поток: так движок не получает параллельных запросов.
    private val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private var engine: Engine? = null
    private var loadedKey: String? = null

    /** Промпт ВСЕГДА содержит класс и предмет, выбранные учеником. */
    fun prompt(grade: Int, subject: String, p: Persona, a: Act, text: String, hasImg: Boolean): String =
        "Ты помощник ученика $grade класса украинской школы (программа НУШ). Предмет: $subject. " +
            "Подбирай объяснение под уровень $grade класса. " +
            "Отвечай на украинском, если задание на украинском, иначе на языке задания. " +
            "Формулы и вычисления пиши отдельными строками. ${p.style} ${a.ask}" +
            (if (text.isNotBlank()) "\n\nУсловие от ученика:\n$text" else "") +
            (if (hasImg) "\nУсловие на фото." else "") +
            "\nВ конце одной строкой напиши, какую тему стоит повторить."

    /** Загружает модель в память (первый раз до минуты). */
    suspend fun prepare(ctx: Context, modelPath: String, gpu: Boolean) = withContext(worker) {
        val key = "$modelPath|$gpu"
        if (engine == null || loadedKey != key) {
            engine?.close(); engine = null
            val e = Engine(
                EngineConfig(
                    modelPath = modelPath,
                    backend = if (gpu) Backend.GPU() else Backend.CPU(),
                    visionBackend = Backend.CPU(),
                    cacheDir = ctx.cacheDir.path
                )
            )
            e.initialize()
            engine = e
            loadedKey = key
        }
    }

    /** Потоковый ответ локальной модели (без интернета). */
    fun stream(text: String, imgPath: String?): Flow<String> = flow {
        val e = engine ?: error("Модель не загружена")
        e.createConversation().use { c ->
            val msg = if (imgPath != null) Contents.of(Content.ImageFile(imgPath), Content.Text(text))
            else Contents.of(Content.Text(text))
            c.sendMessageAsync(msg).collect { emit(it.toString()) }
        }
    }.flowOn(worker)

    /** Онлайн-вариант: бесплатный ключ Google AI Studio. */
    suspend fun gemini(key: String, model: String, prompt: String, img: String?): String = withContext(Dispatchers.IO) {
        val parts = JSONArray().put(JSONObject().put("text", prompt))
        if (img != null) {
            val b64 = Base64.encodeToString(File(img).readBytes(), Base64.NO_WRAP)
            parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b64)))
        }
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", parts)))
        val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key")
            .openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.setRequestProperty("Content-Type", "application/json")
        c.doOutput = true
        c.connectTimeout = 20000
        c.readTimeout = 90000
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val s = (if (code < 400) c.inputStream else c.errorStream).bufferedReader().readText()
        if (code >= 400) error(runCatching { JSONObject(s).getJSONObject("error").getString("message") }.getOrDefault("HTTP $code"))
        val ps = JSONObject(s).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
        (0 until ps.length()).joinToString("") { ps.getJSONObject(it).optString("text") }
    }

    /** Скачивание модели с докачкой. После скачивания интернет больше не нужен. */
    suspend fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        var have = if (part.exists()) part.length() else 0L
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 30000
        if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
        val code = c.responseCode
        if (code == 416) { part.renameTo(dest); return@withContext }
        if (code != 200 && code != 206) error("Сервер ответил HTTP $code")
        if (code == 200) have = 0
        val total = have + c.contentLengthLong
        FileOutputStream(part, have > 0).use { out ->
            c.inputStream.use { inp ->
                val buf = ByteArray(256 * 1024)
                var done = have
                var lastTick = 0L
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    ensureActive()
                    val now = System.currentTimeMillis()
                    if (now - lastTick > 400) { lastTick = now; onProgress(done, total) }
                }
                onProgress(done, total)
            }
        }
        part.renameTo(dest)
    }
}
