package org.doorcam.camera

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

object PairingClient {
    fun exchange(hubUrl: String, code: String, name: String): CameraConfig {
        val normalized = hubUrl.trim().trimEnd('/')
        val uri = URI(normalized)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank()) {
            "Enter the hub HTTPS URL, such as https://192.168.1.225:8443"
        }
        val connection = URL("$normalized/api/pairing/exchange").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        try {
            connection.outputStream.use {
                it.write(JSONObject().put("code", code.trim()).put("name", name.trim())
                    .toString().toByteArray(Charsets.UTF_8))
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = JSONObject(body.ifBlank { "{}" })
            if (status !in 200..299) error(json.optString("detail", "Pairing failed (HTTP $status)"))
            return CameraConfig(normalized, json.getString("camera_id"), json.getString("device_token"))
        } finally {
            connection.disconnect()
        }
    }
}

