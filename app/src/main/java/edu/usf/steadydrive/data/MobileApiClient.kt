package edu.usf.steadydrive.data

import android.os.Build
import edu.usf.steadydrive.model.AssignedDeviceConfig
import edu.usf.steadydrive.model.DriveSessionSummary
import edu.usf.steadydrive.model.ParticipantPhase
import edu.usf.steadydrive.model.ScheduledDrive
import edu.usf.steadydrive.service.PostedSpeedLimit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.TimeZone

class MobileApiClient(
    private val baseUrl: String,
) {
    suspend fun registerDevice(
        credentials: StudyPreferences.InstallationCredentials,
    ) = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("installationId", credentials.installationId)
            .put("deviceSecret", credentials.deviceSecret)
            .put("deviceLabel", Build.MODEL)
            .put("deviceModel", Build.MODEL)
            .put("manufacturer", Build.MANUFACTURER)
            .put("osVersion", Build.VERSION.RELEASE)
            .put("appVersion", "0.1.0")
            .put("timeZone", TimeZone.getDefault().id)

        postJson("/api/mobile/register", payload)
    }

    suspend fun fetchConfig(
        credentials: StudyPreferences.InstallationCredentials,
    ): RemoteConfigResult = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("installationId", credentials.installationId)
            .put("deviceSecret", credentials.deviceSecret)

        val response = postJson("/api/mobile/config", payload)
        val status = response.getString("status")
        if (status == "pending") {
            return@withContext RemoteConfigResult.Pending
        }

        val phase =
            ParticipantPhase.fromWireValue(response.getString("phase"))
                ?: throw IOException("Unknown participant phase returned by server.")
        val schedulesJson = response.optJSONArray("schedules") ?: JSONArray()
        val schedules = buildList {
            for (index in 0 until schedulesJson.length()) {
                val schedule = schedulesJson.getJSONObject(index)
                add(
                    ScheduledDrive(
                        dayOfWeek = schedule.getInt("dayOfWeek"),
                        startTime =
                            if (schedule.isNull("startTime")) {
                                null
                            } else {
                                schedule.getString("startTime").ifBlank { null }
                            },
                    ),
                )
            }
        }

        RemoteConfigResult.Assigned(
            config = AssignedDeviceConfig(
                deviceId = response.getString("deviceId"),
                participantId = response.getString("participantId"),
                phase = phase,
                weeklyRoutineNotes =
                    if (response.isNull("weeklyRoutineNotes")) {
                        null
                    } else {
                        response.getString("weeklyRoutineNotes").ifBlank { null }
                    },
                schedules = schedules,
            ),
        )
    }

    suspend fun uploadSessionPackage(
        credentials: StudyPreferences.InstallationCredentials,
        summary: DriveSessionSummary,
        csvFile: File,
    ): UploadResult = withContext(Dispatchers.IO) {
        val fields =
            linkedMapOf(
                "installationId" to credentials.installationId,
                "deviceSecret" to credentials.deviceSecret,
                "sessionId" to summary.sessionId,
                "participantId" to summary.participantId,
                "phase" to summary.phase,
                "startedAt" to summary.startedAt,
                "endedAt" to summary.endedAt,
                "localFileName" to summary.localFileName,
                "downloadsFileName" to (summary.downloadsFileName ?: ""),
                "samplesRecorded" to summary.samplesRecorded.toString(),
                "averageSpeedMph" to (summary.averageSpeedMph?.toString() ?: ""),
                "peakSpeedMph" to (summary.peakSpeedMph?.toString() ?: ""),
                "maxOverLimitMph" to (summary.maxOverLimitMph?.toString() ?: ""),
                "speedingSeconds" to summary.speedingSeconds.toString(),
                "completionStatus" to summary.completionStatus,
            )

        return@withContext runCatching {
            postMultipart("/api/mobile/session", fields, csvFile)
            UploadResult.Success
        }.getOrElse { error ->
            when (error) {
                is ApiException ->
                    when {
                        error.statusCode == 408 ||
                            error.statusCode == 425 ||
                            error.statusCode == 429 ||
                            error.statusCode >= 500 -> UploadResult.RetryableFailure(error.message)

                        else -> UploadResult.PermanentFailure(error.message)
                    }

                is IOException -> UploadResult.RetryableFailure(error.message)
                else -> UploadResult.RetryableFailure(error.message)
            }
        }
    }

    suspend fun lookupSpeedLimit(
        credentials: StudyPreferences.InstallationCredentials,
        latitude: Double,
        longitude: Double,
    ): PostedSpeedLimit = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("installationId", credentials.installationId)
            .put("deviceSecret", credentials.deviceSecret)
            .put("latitude", latitude)
            .put("longitude", longitude)

        return@withContext runCatching {
            val response = postJson("/api/mobile/speed-limit", payload)
            PostedSpeedLimit(
                speedLimitMph =
                    if (response.isNull("speedLimitMph")) {
                        null
                    } else {
                        response.getDouble("speedLimitMph")
                    },
                source = response.optString("source").ifBlank { null },
                confidence =
                    if (response.isNull("confidence")) {
                        null
                    } else {
                        response.getDouble("confidence")
                    },
            )
        }.getOrElse {
            PostedSpeedLimit(
                speedLimitMph = null,
                source = "speed_limit_lookup_error",
                confidence = 0.0,
            )
        }
    }

    private fun postJson(path: String, payload: JSONObject): JSONObject {
        val connection = createConnection(path)
        connection.setRequestProperty("Content-Type", "application/json")

        OutputStreamWriter(connection.outputStream).use { writer ->
            writer.write(payload.toString())
            writer.flush()
        }

        return readJsonResponse(connection)
    }

    private fun postMultipart(
        path: String,
        fields: Map<String, String>,
        csvFile: File,
    ): JSONObject {
        val boundary = "SteadyDrive${System.currentTimeMillis()}"
        val connection = createConnection(path)
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

        DataOutputStream(connection.outputStream).use { output ->
            fields.forEach { (name, value) ->
                output.writeBytes("--$boundary\r\n")
                output.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                output.write(value.toByteArray(StandardCharsets.UTF_8))
                output.writeBytes("\r\n")
            }

            output.writeBytes("--$boundary\r\n")
            output.writeBytes(
                "Content-Disposition: form-data; name=\"file\"; filename=\"${escapeHeaderValue(csvFile.name)}\"\r\n",
            )
            output.writeBytes("Content-Type: text/csv\r\n\r\n")
            csvFile.inputStream().use { input ->
                input.copyTo(output)
            }
            output.writeBytes("\r\n")
            output.writeBytes("--$boundary--\r\n")
            output.flush()
        }

        return readJsonResponse(connection)
    }

    private fun createConnection(path: String): HttpURLConnection {
        val url = URL("${baseUrl.trimEnd('/')}$path")
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doInput = true
            doOutput = true
        }
    }

    private fun readJsonResponse(connection: HttpURLConnection): JSONObject {
        val statusCode = connection.responseCode
        val sourceStream =
            if (statusCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: connection.inputStream
            }

        val responseText = BufferedReader(InputStreamReader(sourceStream)).use { reader ->
            buildString {
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    append(line)
                }
            }
        }

        if (statusCode !in 200..299) {
            throw ApiException(statusCode, responseText)
        }

        return if (responseText.isBlank()) JSONObject() else JSONObject(responseText)
    }

    private fun escapeHeaderValue(value: String): String = value.replace("\"", "")

    sealed interface RemoteConfigResult {
        data object Pending : RemoteConfigResult
        data class Assigned(val config: AssignedDeviceConfig) : RemoteConfigResult
    }

    sealed interface UploadResult {
        data object Success : UploadResult
        data class RetryableFailure(val message: String?) : UploadResult
        data class PermanentFailure(val message: String?) : UploadResult
    }

    private class ApiException(
        val statusCode: Int,
        responseText: String,
    ) : IOException("HTTP $statusCode: $responseText")
}
