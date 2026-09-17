package com.evercare.app.data

import com.evercare.app.domain.AiAssistant
import com.evercare.app.domain.ChatMessage
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.headers
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Talks to any OpenAI-compatible chat endpoint.
 *
 * This one implementation covers both product modes. EverCare's own server
 * speaks the same protocol as DeepSeek or OpenAI, so "use your own provider" is
 * a different [AssistantEndpoint], not a second client.
 */
class ChatClient(
    private val httpClient: HttpClient,
    private val endpointProvider: suspend () -> AssistantEndpoint?,
) : AiAssistant {

    override fun ask(messages: List<ChatMessage>, language: String): Flow<String> = flow {
        val endpoint = endpointProvider() ?: throw AssistantNotConfigured()

        val request = ChatCompletionRequest(
            model = endpoint.model,
            messages = messages.map { WireMessage(it.role.wire, it.content) },
            stream = true,
            language = language,
        )

        httpClient.preparePost("${endpoint.baseUrl}/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            headers {
                append(HttpHeaders.Authorization, "Bearer ${endpoint.bearerToken}")
                append(HttpHeaders.Accept, ContentType.Text.EventStream.toString())
            }
            setBody(request)
        }.execute { response ->
            if (response.status != HttpStatusCode.OK) {
                throw response.status.toAssistantError(response.bodyAsText())
            }
            emitDeltas(response.bodyAsChannel())
        }
    }

    /**
     * Parses the server-sent event stream: `data: {json}` lines, ending with
     * `data: [DONE]`. Malformed chunks are skipped rather than failing the whole
     * answer -- providers differ in what they put in keep-alive comments.
     */
    private suspend fun FlowCollector<String>.emitDeltas(channel: ByteReadChannel) {
        while (true) {
            val line = channel.readUTF8Line() ?: break
            if (line.isBlank()) continue
            if (!line.startsWith("data:")) continue

            val payload = line.removePrefix("data:").trim()
            if (payload.isEmpty()) continue
            if (payload == DONE) break

            val chunk = runCatching { lenientJson.decodeFromString<ChatCompletionChunk>(payload) }
                .getOrNull()
                ?: continue

            val delta = chunk.choices.firstOrNull()?.delta?.content ?: continue
            if (delta.isNotEmpty()) emit(delta)
        }
    }

    private companion object {
        const val DONE = "[DONE]"

        val lenientJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
        }
    }
}

private fun HttpStatusCode.toAssistantError(body: String): AssistantError = when (this) {
    HttpStatusCode.Unauthorized -> AssistantUnauthorized(body)
    HttpStatusCode.PaymentRequired -> SubscriptionExpired(body)
    else -> AssistantHttpError(value, body)
}

/** Errors the UI turns into gentle, non-technical sentences. */
sealed class AssistantError(message: String) : Exception(message)

class AssistantNotConfigured : AssistantError("assistant is not configured")
class AssistantUnauthorized(body: String) : AssistantError("unauthorized: $body")
class SubscriptionExpired(body: String) : AssistantError("subscription expired: $body")
class AssistantHttpError(val status: Int, body: String) : AssistantError("http $status: $body")

private val ChatMessage.Role.wire: String
    get() = when (this) {
        ChatMessage.Role.System -> "system"
        ChatMessage.Role.User -> "user"
        ChatMessage.Role.Assistant -> "assistant"
    }

@Serializable
private data class ChatCompletionRequest(
    val model: String,
    val messages: List<WireMessage>,
    val stream: Boolean,
    /**
     * EverCare extension, ignored by third-party providers: it tells our server
     * which language to write the system prompt in.
     */
    val language: String? = null,
)

@Serializable
private data class WireMessage(
    val role: String,
    val content: String,
)

@Serializable
private data class ChatCompletionChunk(
    val choices: List<Choice> = emptyList(),
) {
    @Serializable
    data class Choice(
        val delta: Delta? = null,
        @SerialName("finish_reason") val finishReason: String? = null,
    )

    @Serializable
    data class Delta(
        val content: String? = null,
    )
}

/** JSON content negotiation, shared by every platform's HTTP client. */
fun HttpClientConfig<*>.installEverCareDefaults() {
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
        )
    }
}
