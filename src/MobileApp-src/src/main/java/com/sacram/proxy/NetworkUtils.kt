package com.sacram.proxy

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Resilient selection of the outbound (cellular) network.
 *
 * The proxy must bind every upstream socket/DNS lookup to the phone's cellular
 * data interface, but the single [ConnectivityManager.NetworkCallback]-held
 * reference used elsewhere can be nulled out by a transient [android.net.ConnectivityManager.NetworkCallback.onLost]
 * (frequent while the device is a WiFi-Direct Group Owner). When that happens
 * all egress silently fell back to the WiFi-Direct interface (no internet),
 * taking the whole proxy down until the callback refired.
 *
 * This helper re-validates the preferred network on every use and, failing that,
 * scans [ConnectivityManager.getAllNetworks] for a cellular network that
 * actually has internet, only then falling back to the active network.
 */
object NetworkUtils {

    fun pickCellular(cm: ConnectivityManager, preferred: Network?): Network? {
        if (isValidCellular(cm, preferred)) return preferred
        val nets = runCatching { cm.allNetworks }.getOrNull().orEmpty()
        for (n in nets) {
            if (isValidCellular(cm, n)) return n
        }
        // Fallback: any network that actually has internet (e.g. WiFi, or a
        // carrier/VPN path), not just cellular. The WiFi-Direct P2P interface
        // lacks NET_CAPABILITY_INTERNET, so it is never selected here - we still
        // never silently route egress to the dead P2P path.
        for (n in nets) {
            if (isValidEgress(cm, n)) return n
        }
        return null
    }

    private fun isValidCellular(cm: ConnectivityManager, n: Network?): Boolean {
        if (n == null) return false
        val caps = runCatching { cm.getNetworkCapabilities(n) }.getOrNull() ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun isValidEgress(cm: ConnectivityManager, n: Network?): Boolean {
        if (n == null) return false
        val caps = runCatching { cm.getNetworkCapabilities(n) }.getOrNull() ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
