package com.sacram.proxy

import kotlinx.coroutines.flow.MutableStateFlow

data class ApInfo(
    val ssid: String = "",
    val passphrase: String = "",
    val goIp: String = "",
    val clients: Int = 0,
    val panelPort: Int = 0
)

object AppState {
    val status = MutableStateFlow("Stopped")
    val apInfo = MutableStateFlow(ApInfo())
    val running = MutableStateFlow(false)
    val httpMode = MutableStateFlow(false)
    val tcpTunnels = MutableStateFlow(0)
    var serviceStartedAt: Long = 0L
}
