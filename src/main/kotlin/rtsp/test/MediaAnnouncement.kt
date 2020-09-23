package rtsp.test

data class MediaAnnouncement(
        val media: String,
        val port: Int,
        val numberOfPorts: Int,
        val transport: String,
        val fmtList: List<Int>
)