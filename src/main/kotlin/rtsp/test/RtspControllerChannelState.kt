package rtsp.test

data class RtspControllerChannelState(
        val announcements: MutableSet<MediaAnnouncement>,
        val rtpMappings: MutableSet<RtpMapping>,
        var accessedLastTime: Long
) {
    companion object {
        fun create(): RtspControllerChannelState = RtspControllerChannelState(HashSet(), HashSet(), System.currentTimeMillis())
    }
}