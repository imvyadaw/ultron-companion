package com.ultron.companion
import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecureStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context, "ultron_secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    var pcHost: String get() = prefs.getString("pc_host","") ?: "" ; set(v) = prefs.edit().putString("pc_host",v).apply()
    val deviceId: String? get() = prefs.getString("device_id",null)
    val token: String? get() = prefs.getString("token",null)
    fun savePair(host:String,id:String,tok:String){ prefs.edit().putString("pc_host",host).putString("device_id",id).putString("token",tok).apply() }
    fun clearPair(){ prefs.edit().remove("device_id").remove("token").apply() }
}
