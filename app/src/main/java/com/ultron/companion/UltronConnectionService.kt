package com.ultron.companion

import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

class UltronConnectionService : Service() {
    private lateinit var client: UltronClient
    override fun onCreate() {
        super.onCreate(); createChannel()
        startForeground(42, notification("connecting"))
        client = UltronClient.get(applicationContext)
        client.addStateListener(::update)
        client.connect()
    }
    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("ultron", "ULTRON Connection", NotificationManager.IMPORTANCE_LOW)
        )
    }
    private fun notification(state: String) = NotificationCompat.Builder(this, "ultron")
        .setContentTitle("ULTRON Companion").setContentText("Connection: $state")
        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setOngoing(true).build()
    private fun update(s: String) { getSystemService(NotificationManager::class.java).notify(42, notification(s)) }
    override fun onStartCommand(i: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onDestroy() { if (::client.isInitialized) client.removeStateListener(::update); super.onDestroy() }
    override fun onBind(i: Intent?): IBinder? = null
}
