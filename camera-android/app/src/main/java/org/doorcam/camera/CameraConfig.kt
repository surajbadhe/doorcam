package org.doorcam.camera

data class CameraConfig(
    val hubUrl: String,
    val cameraId: String,
    val deviceToken: String,
    val audioEnabled: Boolean = true,
    val cameraFacing: String = "BACK",
    val videoQuality: String = "720p",
    val cameraZoom: Float = 1.0f,
)

