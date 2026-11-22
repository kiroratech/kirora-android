package com.kirora.androidsdk.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.kirora.androidsdk.utils.KiroraLogger

internal class KiroraNetworkMonitor(
    context: Context,
    private val onNetworkRestored: () -> Unit
) {
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    /**
     * The networks up now, guarded by itself. A return is going from none to one, once however
     * many then appear. Seeded with the network up at start, so registering, which reports the
     * networks already up, is no return.
     */
    private val up = HashSet<Network>()

    fun start() {
        if (connectivityManager == null) return

        try {
            activeInternet(connectivityManager)?.let { synchronized(up) { up.add(it) } }
            val builder = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val returned = synchronized(up) { up.isEmpty().also { up.add(network) } }
                    if (!returned) return
                    KiroraLogger.d("Network connectivity restored. Triggering event flush...")
                    onNetworkRestored()
                }

                override fun onLost(network: Network) {
                    synchronized(up) { up.remove(network) }
                    KiroraLogger.d("Network connectivity lost.")
                }
            }

            this.networkCallback = callback
            connectivityManager.registerNetworkCallback(builder.build(), callback)
        } catch (e: Exception) {
            KiroraLogger.w("Could not register network callback", e)
        }
    }

    /** The network up now with internet, or null. */
    private fun activeInternet(manager: ConnectivityManager): Network? = try {
        manager.activeNetwork?.takeIf {
            manager.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }
    } catch (_: Exception) {
        // Can't tell: at worst the first network reported counts as a return, one flush too many.
        null
    }

    fun stop() {
        try {
            networkCallback?.let {
                connectivityManager?.unregisterNetworkCallback(it)
            }
            networkCallback = null
        } catch (e: Exception) {
            KiroraLogger.w("Could not unregister network callback", e)
        }
    }
}
