package org.doorcam.camera

import org.json.JSONObject
import java.net.URI

data class PairingInvite(val hubUrl: String, val code: String)

object PairingInviteParser {
    fun parse(rawValue: String): PairingInvite {
        val json = JSONObject(rawValue)
        require(json.optString("type") == "doorcam-pair-v1") {
            "This QR code is not a DoorCam pairing code."
        }
        val hubUrl = json.getString("hub_url").trim().trimEnd('/')
        val uri = URI(hubUrl)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null &&
            (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")) {
            "The QR code must contain a hub HTTPS address."
        }
        val code = json.getString("code").trim()
        require(code.length in 8..128) { "The QR code has an invalid pairing code." }
        return PairingInvite(hubUrl, code)
    }
}
