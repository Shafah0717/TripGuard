package com.tripguard.app.data.remote

import com.tripguard.app.data.dataset.DatasetDto
import com.tripguard.app.domain.model.AppError
import com.tripguard.app.domain.model.AppResult
import com.tripguard.app.domain.model.ErrorKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Hand-written OkHttp client for the documented TripGuard FastAPI contract. All calls return
 * [AppResult] so the repository layer can degrade gracefully when the backend is unavailable.
 */
class TripGuardApi(
    private val baseUrlProvider: () -> String,
    private val json: Json = DEFAULT_JSON,
    client: OkHttpClient = defaultClient(),
) {
    private val client: OkHttpClient = client
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private fun url(path: String): String =
        baseUrlProvider().trimEnd('/') + "/" + path.trimStart('/')

    suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url("health")).get().build()
            client.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    suspend fun fetchDataset(): AppResult<DatasetDto> =
        get("api/v1/dataset", DatasetDto.serializer())

    suspend fun fetchDocumentPolicy(): AppResult<DocumentPolicyDto> =
        get("api/v1/config/document-policy", DocumentPolicyDto.serializer())

    suspend fun createApplication(body: CreateApplicationRequest): AppResult<RemoteApplicationDto> {
        val text = json.encodeToString(CreateApplicationRequest.serializer(), body)
        return postRawJson("api/v1/applications", text, RemoteApplicationDto.serializer())
    }

    suspend fun fetchApplication(id: String): AppResult<RemoteApplicationDto> =
        get("api/v1/applications/$id", RemoteApplicationDto.serializer())

    suspend fun requestManualReview(
        applicationId: String,
        documentId: String,
        reason: String?,
    ): AppResult<RemoteDocumentDto> {
        val path = "api/v1/applications/$applicationId/documents/$documentId/manual-review"
        val payload = json.encodeToString(ManualReviewRequest.serializer(), ManualReviewRequest(reason))
        return postRawJson(path, payload, RemoteDocumentDto.serializer())
    }

    /**
     * Records the traveler's explicit document-sharing consent. The backend is the only source
     * of a confirmed handoff; the client never assumes success.
     */
    suspend fun confirmDocumentSharing(
        applicationId: String,
        selectedDocumentIds: List<String>,
    ): AppResult<RemoteApplicationDto> {
        val path = "api/v1/applications/$applicationId/documents/share-consent"
        val payload = json.encodeToString(
            ShareConsentRequest.serializer(),
            ShareConsentRequest(selectedDocumentIds = selectedDocumentIds, consent = true),
        )
        return postRawJson(path, payload, RemoteApplicationDto.serializer())
    }

    /** Sends an accept / change / decline response for an agency appointment proposal. */
    suspend fun respondToAppointment(
        applicationId: String,
        appointmentId: String,
        request: AppointmentResponseRequest,
    ): AppResult<RemoteApplicationDto> {
        val path = "api/v1/applications/$applicationId/appointments/$appointmentId/respond"
        val payload = json.encodeToString(AppointmentResponseRequest.serializer(), request)
        return postRawJson(path, payload, RemoteApplicationDto.serializer())
    }

    // ---- Agency-assistance flow (shared Agency API contract) ----------------

    /**
     * Contact-inquiry, slot and booking calls. The request DTOs carry only
     * consented contact fields, visa route, optional aggregate summary and the
     * consent record — never documents, file paths, OCR text, or OCR-derived
     * personal fields.
     */

    suspend fun createAgencyInquiry(
        body: CreateInquiryRequestDto,
    ): AppResult<InquiryCreatedDto> {
        val text = toAgencyJson(CreateInquiryRequestDto.serializer(), body)
        return postRawJson("api/inquiries", text, InquiryCreatedDto.serializer())
    }

    suspend fun fetchAgencyInquiry(travellerToken: String): AppResult<InquiryStatusDto> =
        getWithToken("api/inquiries/$travellerToken", InquiryStatusDto.serializer(), travellerToken)

    suspend fun fetchAgencySlots(travellerToken: String): AppResult<List<AgencySlotDto>> =
        getWithToken(
            "api/inquiries/$travellerToken/slots",
            ListSerializer(AgencySlotDto.serializer()),
            travellerToken,
        )

    suspend fun createAgencyBooking(
        travellerToken: String,
        body: CreateBookingRequestDto,
    ): AppResult<BookingDto> {
        val text = toAgencyJson(CreateBookingRequestDto.serializer(), body)
        return postWithToken(
            "api/inquiries/$travellerToken/bookings",
            text,
            BookingDto.serializer(),
            travellerToken,
        )
    }

    suspend fun fetchAgencyBooking(
        bookingReference: String,
        travellerToken: String,
    ): AppResult<BookingDto> =
        getWithToken("api/bookings/$bookingReference", BookingDto.serializer(), travellerToken)

    suspend fun cancelAgencyBooking(
        bookingReference: String,
        travellerToken: String,
    ): AppResult<BookingDto> =
        postWithToken(
            "api/bookings/$bookingReference/cancel",
            "{}",
            BookingDto.serializer(),
            travellerToken,
        )

    /** Withdraws the retained inquiry entirely (traveller-initiated close). */
    suspend fun withdrawInquiry(travellerToken: String): AppResult<Unit> =
        deleteWithToken("api/inquiries/$travellerToken", travellerToken)

    private suspend fun deleteWithToken(
        path: String,
        travellerToken: String,
    ): AppResult<Unit> = withContext(Dispatchers.IO) {
        try {
            client.newCall(
                Request.Builder().url(url(path)).delete()
                    .header("X-Traveller-Token", travellerToken)
                    .build(),
            ).execute().use { response ->
                if (response.isSuccessful) {
                    AppResult.Success(Unit)
                } else {
                    AppResult.Failure(classifyHttp(response.code, response.body?.string().orEmpty()))
                }
            }
        } catch (t: Throwable) {
            AppResult.Failure(classify(t))
        }
    }

    private fun <T> toAgencyJson(
        serializer: SerializationStrategy<T>,
        value: T,
    ): String {
        val element = json.encodeToJsonElement(serializer, value)
        return json.encodeToString(JsonElement.serializer(), toSnakeKeys(element))
    }

    private fun toSnakeKeys(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.entries.associate { (key, value) ->
                key.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase() to toSnakeKeys(value)
            },
        )
        is JsonArray -> JsonArray(element.map { toSnakeKeys(it) })
        else -> element
    }

    private suspend fun <T> getWithToken(
        path: String,
        deserializer: DeserializationStrategy<T>,
        travellerToken: String,
    ): AppResult<T> = withContext(Dispatchers.IO) {
        execute(
            Request.Builder().url(url(path)).get()
                .header("X-Traveller-Token", travellerToken)
                .build(),
            deserializer,
        )
    }

    private suspend fun <T> postWithToken(
        path: String,
        text: String,
        deserializer: DeserializationStrategy<T>,
        travellerToken: String,
    ): AppResult<T> = withContext(Dispatchers.IO) {
        execute(
            Request.Builder().url(url(path)).post(text.toRequestBody(jsonMedia))
                .header("X-Traveller-Token", travellerToken)
                .build(),
            deserializer,
        )
    }

    suspend fun uploadDocument(
        applicationId: String,
        documentId: String,
        file: File,
        mimeType: String?,
        onProgress: (Float) -> Unit,
    ): AppResult<RemoteDocumentDto> = withContext(Dispatchers.IO) {
        try {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("documentId", documentId)
                .addFormDataPart(
                    "file",
                    file.name,
                    ProgressRequestBody(file, (mimeType ?: "application/octet-stream").toMediaType(), onProgress),
                )
                .build()

            val request = Request.Builder()
                .url(url("api/v1/applications/$applicationId/documents/upload"))
                .post(body)
                .build()

            execute(request, RemoteDocumentDto.serializer())
        } catch (t: Throwable) {
            AppResult.Failure(classify(t))
        }
    }

    private suspend fun <T> get(
        path: String,
        deserializer: DeserializationStrategy<T>,
    ): AppResult<T> = withContext(Dispatchers.IO) {
        execute(Request.Builder().url(url(path)).get().build(), deserializer)
    }

    private suspend fun <T> postRawJson(
        path: String,
        text: String,
        deserializer: DeserializationStrategy<T>,
    ): AppResult<T> = withContext(Dispatchers.IO) {
        execute(Request.Builder().url(url(path)).post(text.toRequestBody(jsonMedia)).build(), deserializer)
    }

    private fun <T> execute(request: Request, deserializer: DeserializationStrategy<T>): AppResult<T> {
        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    AppResult.Failure(classifyHttp(response.code, body))
                } else {
                    AppResult.Success(json.decodeFromString(deserializer, body))
                }
            }
        } catch (t: Throwable) {
            AppResult.Failure(classify(t))
        }
    }

    private fun classifyHttp(code: Int, body: String): AppError {
        val message = runCatching {
            json.decodeFromString(ApiErrorDto.serializer(), body).let { it.detail ?: it.message }
        }.getOrNull() ?: "Request failed with status $code."
        val kind = if (code == 404) ErrorKind.NOT_FOUND else ErrorKind.SERVER
        return AppError(kind, message)
    }

    private fun classify(t: Throwable): AppError = when (t) {
        is SocketTimeoutException -> AppError(ErrorKind.TIMEOUT, "The server took too long to respond.", t)
        is UnknownHostException -> AppError(ErrorKind.OFFLINE, "Backend unreachable. Check the server address.", t)
        is ConnectException -> AppError(ErrorKind.NETWORK, "Could not connect to the backend.", t)
        is SerializationException -> AppError(ErrorKind.PARSE, "The server returned an unexpected response.", t)
        else -> AppError(ErrorKind.UNKNOWN, t.message ?: "Unexpected error.", t)
    }

    companion object {
        val DEFAULT_JSON: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            explicitNulls = false
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}

