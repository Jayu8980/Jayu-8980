package com.gateee.app.data

import com.gateee.core.GateJson
import com.gateee.core.GenerateRequest
import com.gateee.core.Paper
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
data class JobStatus(
    val id: String,
    val status: String,
    val stage: String = "",
    val progress: Double = 0.0,
    val log: List<String> = emptyList(),
    val error: String? = null,
    @SerialName("question_count") val questionCount: Int = 0,
)

@Serializable
private data class JobCreated(@SerialName("job_id") val jobId: String)

@Serializable
private data class ReportBody(@SerialName("question_id") val questionId: String, val reason: String)

/**
 * Talks to the backend that holds the LLM key and runs the multi-agent pipeline.
 * The APK never contains an API key; it only knows the backend URL and an optional app token.
 */
class BackendClient(private val settings: SettingsStore) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json".toMediaType()

    private suspend fun request(path: String, body: String? = null): String = withContext(Dispatchers.IO) {
        val s = settings.current()
        val b = Request.Builder().url(s.backendUrl.trimEnd('/') + path)
        if (s.clientToken.isNotBlank()) b.header("Authorization", "Bearer ${s.clientToken}")
        if (body != null) b.post(body.toRequestBody(jsonType))
        http.newCall(b.build()).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                val msg = runCatching { GateJson.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
                throw IOException(msg ?: "Server error ${res.code}")
            }
            text
        }
    }

    suspend fun health(): String = request("/health")

    suspend fun startGeneration(req: GenerateRequest): String =
        GateJson.decodeFromString<JobCreated>(request("/api/generate", GateJson.encodeToString(req))).jobId

    suspend fun job(jobId: String): JobStatus = GateJson.decodeFromString(request("/api/jobs/$jobId"))

    suspend fun paper(jobId: String): Paper = GateJson.decodeFromString(request("/api/jobs/$jobId/paper"))

    suspend fun reportQuestion(questionId: String, reason: String) {
        request("/api/report-question", GateJson.encodeToString(ReportBody(questionId, reason)))
    }
}
