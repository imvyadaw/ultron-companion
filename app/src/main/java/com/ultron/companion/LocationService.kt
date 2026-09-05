package com.ultron.companion

import android.Manifest
import android.app.*
import android.content.pm.PackageManager
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * "Where is it": while running, periodically pushes the phone's GPS
 * fix to whichever PC ULTRON is paired with, over the same WebSocket
 * UltronClient already keeps open for commands (see
 * UltronClient.sendLocationUpdate, and
 * cross_device/location_tracker.py on the PC side for how these are
 * consumed - latest fix + named-place enter/exit detection).
 *
 * Deliberately simple: fused location at a balanced-power interval,
 * not raw GPS polling - good enough for "is the user home/at office
 * yet", not turn-by-turn navigation.
 *
 * Starts/stops from the Settings tab (see MainActivity.kt's toggle) -
 * never launches itself; ACCESS_FINE_LOCATION (or at least COARSE)
 * must already be granted or this refuses to start.
 */
class LocationService : LifecycleService() {

    companion object {
        private const val TAG = "LocationService"
        private const val NOTIF_ID = 44
        private const val CHANNEL_ID = "ultron_location"
        private const val UPDATE_INTERVAL_MS = 30_000L
    }

    private lateinit var client: UltronClient
    private lateinit var fusedClient: com.google.android.gms.location.FusedLocationProviderClient
    private var callback: LocationCallback? = null

    override fun onCreate() {
        super.onCreate()
        client = UltronClient.get(applicationContext)
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        createChannel()
        startForeground(NOTIF_ID, notification("starting"))

        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) {
            Log.w(TAG, "Neither ACCESS_FINE_LOCATION nor ACCESS_COARSE_LOCATION granted - stopping")
            update("no permission granted"); stopSelf(); return
        }
        startUpdates(hasFine)
        update("live")
    }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    private fun startUpdates(highAccuracy: Boolean) {
        val priority = if (highAccuracy) Priority.PRIORITY_BALANCED_POWER_ACCURACY else Priority.PRIORITY_LOW_POWER
        val request = LocationRequest.Builder(priority, UPDATE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(UPDATE_INTERVAL_MS / 2)
            .build()
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                val sent = client.sendLocationUpdate(loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy else null)
                update(if (sent) "live" else "live (not connected)")
            }
        }
        callback = cb
        try {
            fusedClient.requestLocationUpdates(request, cb, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.e(TAG, "Location request denied: ${e.message}"); update("permission denied"); stopSelf()
        }
    }

    // --------------------------------------------------------------- service
    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "ULTRON Location Sharing", NotificationManager.IMPORTANCE_LOW)
        )
    }
    private fun notification(state: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("ULTRON is tracking location")
        .setContentText("Location sharing: $state")
        .setSmallIcon(android.R.drawable.ic_menu_mylocation).setOngoing(true).build()
    private fun update(state: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(state))
    }

    override fun onDestroy() {
        callback?.let { fusedClient.removeLocationUpdates(it) }
        callback = null
        super.onDestroy()
    }
}
