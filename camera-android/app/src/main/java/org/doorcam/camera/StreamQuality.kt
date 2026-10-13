package org.doorcam.camera

enum class StreamQuality(
    val id: String,
    val label: String,
    val width: Int,
    val height: Int,
    val bitrate: Int,
    val fps: Int = 15,
) {
    SD_480("480p", "480p · low bandwidth · 0.8 Mbps", 854, 480, 800_000),
    HD_720("720p", "720p · balanced · 1.5 Mbps", 1280, 720, 1_500_000),
    FULL_HD_1080("1080p", "1080p · high detail · 3 Mbps", 1920, 1080, 3_000_000);

    companion object {
        fun fromId(id: String): StreamQuality = entries.firstOrNull { it.id == id } ?: HD_720
    }
}
