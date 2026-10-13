package org.doorcam.camera

import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

object DoorbellClient {
    fun ring(config: CameraConfig): String {
        val uri = URI(config.hubUrl)
        val target = URI(uri.scheme, null, uri.host, uri.port,
            "/api/cameras/${config.cameraId}/ring", null, null).toString()
        val connection = URL(target).openConnection() as HttpURLConnection
        try {
            val credentials = Base64.encodeToString(
                "${config.cameraId}:${config.deviceToken}".toByteArray(Charsets.UTF_8), Base64.NO_WRAP,
            )
            connection.requestMethod = "POST"
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("Authorization", "Basic $credentials")
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val detail = runCatching { JSONObject(body).optString("detail") }.getOrDefault("")
                error(detail.ifBlank { "Doorbell request failed (HTTP $status)" })
            }
            return JSONObject(body).optString("status", "ringing")
        } finally {
            connection.disconnect()
        }
    }
}
