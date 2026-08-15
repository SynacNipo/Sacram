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
        for (n in runCatching { cm.allNetworks }.getOrNull().orEmpty()) {
            if (isValidCellular(cm, n)) return n
        }
        return runCatching { cm.activeNetwork }.getOrNull()
    }

    private fun isValidCellular(cm: ConnectivityManager, n: Network?): Boolean {
        if (n == null) return false
        val caps = runCatching { cm.getNetworkCapabilities(n) }.getOrNull() ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
