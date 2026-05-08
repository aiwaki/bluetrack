package dev.xd.bluetrack.ble

internal const val MAX_GATEWAY_EVENTS = 24

internal data class GatewayReduction(
    val status: GatewayStatus,
    val event: GatewayEvent?,
)

internal fun reduceGatewayStatus(
    current: GatewayStatus,
    nowMs: Long,
    maxEvents: Int = MAX_GATEWAY_EVENTS,
    hid: String? = null,
    feedback: String? = null,
    pairing: String? = null,
    compatibility: CompatibilitySnapshot? = null,
    host: String? = current.host,
    error: String? = current.error,
    reportsSent: Int? = null,
    feedbackPackets: Int? = null,
    rejectedFeedbackPackets: Int? = null,
    lastInputSource: String? = current.lastInputSource,
    lastInputAtMs: Long? = current.lastInputAtMs,
    lastReportAtMs: Long? = current.lastReportAtMs,
    lastFeedbackAtMs: Long? = current.lastFeedbackAtMs,
    feedbackPin: String? = current.feedbackPin,
    eventSource: String? = null,
    eventMessage: String? = null,
): GatewayReduction {
    val event = if (!eventSource.isNullOrBlank() && !eventMessage.isNullOrBlank()) {
        GatewayEvent(nowMs, eventSource, eventMessage)
    } else {
        null
    }
    val nextStatus = current.copy(
        hid = hid ?: current.hid,
        feedback = feedback ?: current.feedback,
        pairing = pairing ?: current.pairing,
        compatibility = compatibility ?: current.compatibility,
        host = host,
        error = error,
        reportsSent = reportsSent ?: current.reportsSent,
        feedbackPackets = feedbackPackets ?: current.feedbackPackets,
        rejectedFeedbackPackets = rejectedFeedbackPackets ?: current.rejectedFeedbackPackets,
        lastInputSource = lastInputSource,
        lastInputAtMs = lastInputAtMs,
        lastReportAtMs = lastReportAtMs,
        lastFeedbackAtMs = lastFeedbackAtMs,
        feedbackPin = feedbackPin,
        events = if (event == null) current.events else (listOf(event) + current.events).take(maxEvents),
    )
    return GatewayReduction(nextStatus, event)
}
