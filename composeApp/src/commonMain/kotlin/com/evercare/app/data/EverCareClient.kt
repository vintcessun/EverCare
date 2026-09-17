package com.evercare.app.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The EverCare-specific half of the server API: registering this handset and
 * checking what it is entitled to.
 *
 * Chat itself does not go through here -- that is [ChatClient], which speaks
 * plain OpenAI so the same code works against a third-party provider.
 */
class EverCareClient(private val httpClient: HttpClient) {

    /**
     * Registers this handset and returns its token.
     *
     * Called once, on first run, without asking the user for anything. The
     * subscription is bound to the device id that comes back.
     */
    suspend fun registerDevice(
        serverUrl: String,
        label: String,
        platform: String,
    ): DeviceRegistration {
        val response = httpClient.post("${serverUrl.trimEnd('/')}/v1/evercare/devices") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(label = label, platform = platform))
        }
        if (response.status != HttpStatusCode.OK) {
            throw AssistantHttpError(response.status.value, "device registration failed")
        }
        return response.body()
    }

    suspend fun subscription(serverUrl: String, deviceToken: String): SubscriptionStatus {
        val response = httpClient.get("${serverUrl.trimEnd('/')}/v1/evercare/subscription") {
            headers { append(HttpHeaders.Authorization, "Bearer $deviceToken") }
        }
        return when (response.status) {
            HttpStatusCode.OK -> response.body()
            HttpStatusCode.Unauthorized -> throw AssistantUnauthorized("device token rejected")
            else -> throw AssistantHttpError(response.status.value, "cannot read subscription")
        }
    }

    /**
     * Moves an existing subscription onto this handset.
     *
     * Used when the user replaces their phone: the new one registers itself and
     * then presents the old device's token once.
     */
    suspend fun transferSubscription(
        serverUrl: String,
        deviceToken: String,
        fromToken: String,
    ): SubscriptionStatus {
        val response = httpClient.post(
            "${serverUrl.trimEnd('/')}/v1/evercare/subscription/transfer"
        ) {
            contentType(ContentType.Application.Json)
            headers { append(HttpHeaders.Authorization, "Bearer $deviceToken") }
            setBody(TransferRequest(fromToken))
        }
        if (response.status != HttpStatusCode.OK) {
            throw AssistantHttpError(response.status.value, "transfer failed")
        }
        return response.body()
    }
}

@Serializable
data class DeviceRegistration(
    @SerialName("device_id") val deviceId: String,
    val token: String,
    val subscription: SubscriptionStatus,
)

@Serializable
data class SubscriptionStatus(
    @SerialName("device_id") val deviceId: String = "",
    val active: Boolean = false,
    val plan: String = "none",
    @SerialName("expires_at") val expiresAt: String = "",
    @SerialName("days_remaining") val daysRemaining: Long = 0,
    @SerialName("auto_renew") val autoRenew: Boolean = false,
)

@Serializable
private data class RegisterRequest(
    val label: String,
    val platform: String,
)

@Serializable
private data class TransferRequest(
    @SerialName("from_token") val fromToken: String,
)
