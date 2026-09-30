package com.papi.nova.profiles

import android.content.Context
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import androidx.annotation.NonNull
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.papi.nova.LimeLog
import java.io.File
import java.io.FileReader
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

class ProfilesManager private constructor() {
    private val profiles: MutableMap<UUID, SettingsProfile> = LinkedHashMap()
    private var activeProfileId: UUID? = null
    private val listeners: MutableList<ProfileChangeListener> = ArrayList()
    private var appContext: Context? = null
    private val snapshotLock = Any()
    private val persistenceLock=Any()
    private val persistenceRevision=java.util.concurrent.atomic.AtomicLong()
    private val persistenceExecutor=java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task,"NovaProfileWriter").apply { isDaemon=true }
    }
    internal var openProfileWriter: (File) -> FileOutputStream = { FileOutputStream(it) }

    fun load(context: Context?): Boolean {
        LimeLog.info("ArtemisProfile: Loading profile...")
        if (context == null) {
            return false
        }

        appContext = try {
            context.applicationContext
        } catch (e: Exception) {
            context
        }

        val safeContext = appContext ?: return false

        try {
            val dir = File(safeContext.filesDir, PROFILES_DIR)
            if (!dir.exists() && !dir.mkdirs()) {
                return false
            }
            val file = File(dir, PROFILES_FILE)
            if (!file.exists()) {
                return true
            }
            var migrated = false
            try {
                FileReader(file).use { reader ->
                    val type = object : TypeToken<ProfilesData>() {}.type
                    val data: ProfilesData? = Gson().fromJson(reader, type)
                    if (data?.profiles != null) {
                        profiles.clear()
                        for (profile in data.profiles.orEmpty()) {
                            migrated = profile.migrateStreamOptions() || migrated
                            profiles[profile.getUuid()] = profile
                        }
                        activeProfileId = data.activeProfileId
                    }
                }
                if (migrated && !save(safeContext)) return false
            } catch (e: IOException) {
                LimeLog.warning("ArtemisProfile: Failed to load profiles from file:$e")
                e.printStackTrace()
                return false
            }
        } catch (e: Exception) {
            LimeLog.warning("ArtemisProfile: Failed to load profiles:$e")
            e.printStackTrace()
            return false
        }

        return true
    }

    private fun snapshotForPersistence(): Pair<String, Long> = synchronized(snapshotLock) {
        val data=ProfilesData().apply {
            profiles=ArrayList(this@ProfilesManager.profiles.values)
            activeProfileId=this@ProfilesManager.activeProfileId
        }
        Gson().toJson(data) to persistenceRevision.incrementAndGet()
    }

    private fun persistSnapshot(context: Context, snapshot: Pair<String, Long>): Boolean = synchronized(persistenceLock) {
        val (json, revision) = snapshot
        if (revision != persistenceRevision.get()) return@synchronized true
        try {
            val dir=File(context.filesDir,PROFILES_DIR)
            check(dir.exists() || dir.mkdirs())
            NovaProfileFile.write(File(dir, PROFILES_FILE), json, openProfileWriter)
            true
        } catch(error:Exception) {
            LimeLog.warning("Nova: Could not save profiles: ${error.message}")
            false
        }
    }

    fun save(context: Context?): Boolean {
        if (context == null) return false
        return persistSnapshot(context, snapshotForPersistence())
    }

    fun getProfiles(): MutableList<SettingsProfile> = ArrayList(profiles.values)

    fun add(profile: SettingsProfile) {
        profiles[profile.getUuid()] = profile
        notifyListeners()
        saveIfPossible()
    }

    fun update(profile: SettingsProfile) {
        profiles[profile.getUuid()] = profile
        notifyListeners()
        saveIfPossible()
    }

    /** Keep selection immediate; serialize an immutable snapshot before handing disk IO to the worker. */
    fun updateDeferred(profile: SettingsProfile) {
        profiles[profile.getUuid()]=profile
        notifyListeners()
        val context=appContext ?: return
        // The snapshot lock is never held by disk IO. Revision checks under the IO lock
        // serialize writers and prevent an older queued snapshot from replacing a newer one.
        val snapshot=snapshotForPersistence()
        persistenceExecutor.execute { persistSnapshot(context, snapshot) }
    }

    internal fun awaitDeferredWritesForTest() = persistenceExecutor.submit {}.get(5,java.util.concurrent.TimeUnit.SECONDS)

    /**
     * Puts [profile] in place of the preset with its id, or adds it, and keeps it only if the file
     * saves. A failed save puts back what was there and returns false, so the list never shows a
     * preset the file does not have. Listeners hear only a change that was kept.
     */
    fun commit(context: Context, profile: SettingsProfile): Boolean {
        val id = profile.getUuid()
        val previous = profiles[id]
        profiles[id] = profile
        if (!save(context)) {
            if (previous != null) profiles[id] = previous else profiles.remove(id)
            return false
        }
        notifyListeners()
        return true
    }

    fun delete(uuid: UUID?) {
        profiles.remove(uuid)
        if (uuid == activeProfileId) {
            activeProfileId = null
        }
        notifyListeners()
        saveIfPossible()
    }

    fun setActive(uuid: UUID?) {
        activeProfileId = uuid
        notifyListeners()
        saveIfPossible()
    }

    fun getActive(): SettingsProfile? {
        return activeProfileId?.let { profiles[it] }
    }

    @NonNull
    fun getActiveName(): String {
        return getActive()?.getName() ?: ""
    }

    fun addListener(listener: ProfileChangeListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: ProfileChangeListener) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        for (listener in listeners) {
            listener.onProfilesChanged()
        }
    }

    fun getOverlayingSharedPreferences(context: Context): SharedPreferences {
        val base = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val active = getActive()
        val options = active?.getOptions()
        return if (options == null) {
            base
        } else {
            OverlaySharedPreferences(base, options)
        }
    }

    private fun saveIfPossible(): Boolean {
        val context = appContext ?: return false
        return save(context)
    }

    private class ProfilesData {
        @JvmField
        var profiles: MutableList<SettingsProfile>? = null

        @JvmField
        var activeProfileId: UUID? = null
    }

    fun interface ProfileChangeListener {
        fun onProfilesChanged()
    }

    private class OverlaySharedPreferences(
        private val base: SharedPreferences,
        private val patch: Map<String, Any>,
    ) : SharedPreferences {
        override fun getAll(): MutableMap<String, *> {
            val combined: MutableMap<String, Any?> = LinkedHashMap(base.all)
            combined.putAll(patch)
            return combined
        }

        override fun getString(key: String?, defValue: String?): String? {
            if (patch.containsKey(key)) return patch[key] as String?
            return base.getString(key, defValue)
        }

        override fun getInt(key: String?, defValue: Int): Int {
            if (patch.containsKey(key)) return (patch[key] as Number).toInt()
            return base.getInt(key, defValue)
        }

        override fun getLong(key: String?, defValue: Long): Long {
            if (patch.containsKey(key)) return (patch[key] as Number).toLong()
            return base.getLong(key, defValue)
        }

        override fun getFloat(key: String?, defValue: Float): Float {
            if (patch.containsKey(key)) return (patch[key] as Number).toFloat()
            return base.getFloat(key, defValue)
        }

        override fun getBoolean(key: String?, defValue: Boolean): Boolean {
            if (patch.containsKey(key)) return patch[key] as Boolean
            return base.getBoolean(key, defValue)
        }

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
            if (patch.containsKey(key)) return patch[key] as MutableSet<String>?
            return base.getStringSet(key, defValues)
        }

        override fun contains(key: String?): Boolean {
            return patch.containsKey(key) || base.contains(key)
        }

        override fun edit(): SharedPreferences.Editor = base.edit()

        override fun registerOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener?) {
            base.registerOnSharedPreferenceChangeListener(listener)
        }

        override fun unregisterOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener?) {
            base.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    companion object {
        private const val PROFILES_DIR = "profiles"
        private const val PROFILES_FILE = "profiles.json"

        @JvmField
        var instance: ProfilesManager? = null

        @JvmStatic
        @Synchronized
        fun getInstance(): ProfilesManager {
            if (instance == null) {
                instance = ProfilesManager()
            }
            return instance!!
        }
    }
}
