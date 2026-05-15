package com.papi.nova.utils

import android.Manifest.permission.ACCESS_WIFI_STATE
import android.Manifest.permission.CHANGE_WIFI_STATE
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException

object DeviceUtils {
    /**
     * Return whether device is rooted.
     *
     * @return {@code true}: yes<br>{@code false}: no
     */
    @JvmStatic
    fun isDeviceRooted(): Boolean {
        val su = "su"
        val locations = arrayOf(
            "/system/bin/",
            "/system/xbin/",
            "/sbin/",
            "/system/sd/xbin/",
            "/system/bin/failsafe/",
            "/data/local/xbin/",
            "/data/local/bin/",
            "/data/local/",
            "/system/sbin/",
            "/usr/bin/",
            "/vendor/bin/",
        )
        for (location in locations) {
            if (File(location + su).exists()) {
                return true
            }
        }
        return false
    }

    /**
     * Return whether ADB is enabled.
     *
     * @return {@code true}: yes<br>{@code false}: no
     */
    @JvmStatic
    @RequiresApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
    fun isAdbEnabled(context: Context): Boolean {
        return Settings.Secure.getInt(
            context.contentResolver,
            Settings.Global.ADB_ENABLED,
            0,
        ) > 0
    }

    /**
     * Return the version name of device's system.
     *
     * @return the version name of device's system
     */
    @JvmStatic
    fun getSDKVersionName(): String = Build.VERSION.RELEASE

    /**
     * Return version code of device's system.
     *
     * @return version code of device's system
     */
    @JvmStatic
    fun getSDKVersionCode(): Int = Build.VERSION.SDK_INT

    /**
     * Return the android id of device.
     *
     * @return the android id of device
     */
    @JvmStatic
    @SuppressLint("HardwareIds")
    fun getAndroidID(context: Context): String {
        val id = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID,
        )
        if ("9774d56d682e549c" == id) return ""
        return id ?: ""
    }

    /**
     * Return the MAC address.
     * <p>Must hold {@code <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />},
     * {@code <uses-permission android:name="android.permission.INTERNET" />},
     * {@code <uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />}</p>
     *
     * @return the MAC address
     */
    @JvmStatic
    @RequiresPermission(allOf = [ACCESS_WIFI_STATE, CHANGE_WIFI_STATE])
    fun getMacAddress(context: Context): String {
        val macAddress = getMacAddress(context, *emptyArray())
        if (macAddress.isNotEmpty() || getWifiEnabled(context)) return macAddress
        setWifiEnabled(context, true)
        setWifiEnabled(context, false)
        return getMacAddress(context, *emptyArray())
    }

    private fun getWifiEnabled(context: Context): Boolean {
        @SuppressLint("WifiManagerLeak")
        val manager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager?
        return manager?.isWifiEnabled ?: false
    }

    /**
     * Enable or disable wifi.
     * <p>Must hold {@code <uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />}</p>
     *
     * @param enabled True to enabled, false otherwise.
     */
    @Suppress("DEPRECATION")
    @RequiresPermission(CHANGE_WIFI_STATE)
    private fun setWifiEnabled(context: Context, enabled: Boolean) {
        @SuppressLint("WifiManagerLeak")
        val manager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager?
        if (manager == null) return
        if (enabled == manager.isWifiEnabled) return
        manager.isWifiEnabled = enabled
    }

    /**
     * Return the MAC address.
     * <p>Must hold {@code <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />},
     * {@code <uses-permission android:name="android.permission.INTERNET" />}</p>
     *
     * @return the MAC address
     */
    @JvmStatic
    @RequiresPermission(allOf = [ACCESS_WIFI_STATE])
    fun getMacAddress(context: Context, vararg excepts: String?): String {
        var macAddress = getMacAddressByNetworkInterface()
        if (isAddressNotInExcepts(macAddress, excepts)) {
            return macAddress
        }
        macAddress = getMacAddressByInetAddress()
        if (isAddressNotInExcepts(macAddress, excepts)) {
            return macAddress
        }
        macAddress = getMacAddressByWifiInfo(context)
        if (isAddressNotInExcepts(macAddress, excepts)) {
            return macAddress
        }
        return ""
    }

    private fun isAddressNotInExcepts(address: String?, excepts: Array<out String?>?): Boolean {
        if (address.isNullOrEmpty()) {
            return false
        }
        if ("02:00:00:00:00:00" == address) {
            return false
        }
        if (excepts.isNullOrEmpty()) {
            return true
        }
        for (filter in excepts) {
            if (filter != null && filter == address) {
                return false
            }
        }
        return true
    }

    @SuppressLint("HardwareIds")
    @RequiresPermission(ACCESS_WIFI_STATE)
    private fun getMacAddressByWifiInfo(context: Context): String {
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager?
            if (wifi != null) {
                val info = wifi.connectionInfo
                if (info != null) {
                    val macAddress = info.macAddress
                    if (!macAddress.isNullOrEmpty()) {
                        return macAddress
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "02:00:00:00:00:00"
    }

    private fun getMacAddressByNetworkInterface(): String {
        try {
            val nis = NetworkInterface.getNetworkInterfaces()
            while (nis.hasMoreElements()) {
                val ni = nis.nextElement()
                if (!ni.name.equals("wlan0", ignoreCase = true)) continue
                val macBytes = ni.hardwareAddress
                if (macBytes != null && macBytes.isNotEmpty()) {
                    val sb = StringBuilder()
                    for (b in macBytes) {
                        sb.append(String.format("%02x:", b))
                    }
                    return sb.substring(0, sb.length - 1)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "02:00:00:00:00:00"
    }

    private fun getMacAddressByInetAddress(): String {
        try {
            val inetAddress = getInetAddress()
            if (inetAddress != null) {
                val ni = NetworkInterface.getByInetAddress(inetAddress)
                if (ni != null) {
                    val macBytes = ni.hardwareAddress
                    if (macBytes != null && macBytes.isNotEmpty()) {
                        val sb = StringBuilder()
                        for (b in macBytes) {
                            sb.append(String.format("%02x:", b))
                        }
                        return sb.substring(0, sb.length - 1)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "02:00:00:00:00:00"
    }

    private fun getInetAddress(): InetAddress? {
        try {
            val nis = NetworkInterface.getNetworkInterfaces()
            while (nis.hasMoreElements()) {
                val ni = nis.nextElement()
                if (!ni.isUp) continue
                val addresses = ni.inetAddresses
                while (addresses.hasMoreElements()) {
                    val inetAddress = addresses.nextElement()
                    if (!inetAddress.isLoopbackAddress) {
                        val hostAddress = inetAddress.hostAddress ?: continue
                        if (hostAddress.indexOf(':') < 0) return inetAddress
                    }
                }
            }
        } catch (e: SocketException) {
            e.printStackTrace()
        }
        return null
    }

    /**
     * Return the manufacturer of the product/hardware.
     * <p>e.g. Xiaomi</p>
     *
     * @return the manufacturer of the product/hardware
     */
    @JvmStatic
    fun getManufacturer(): String = Build.MANUFACTURER

    /**
     * Return the model of device.
     * <p>e.g. MI2SC</p>
     *
     * @return the model of device
     */
    @JvmStatic
    fun getModel(): String {
        val model = Build.MODEL
        return model?.trim()?.replace("\\s*".toRegex(), "") ?: ""
    }

    /**
     * Return an ordered list of ABIs supported by this device. The most preferred ABI is the first
     * element in the list.
     *
     * @return an ordered list of ABIs supported by this device
     */
    @JvmStatic
    fun getABIs(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Build.SUPPORTED_ABIS
        } else if (!Build.CPU_ABI2.isNullOrEmpty()) {
            arrayOf(Build.CPU_ABI, Build.CPU_ABI2)
        } else {
            arrayOf(Build.CPU_ABI)
        }
    }

    /**
     * Return whether device is tablet.
     *
     * @return {@code true}: yes<br>{@code false}: no
     */
    @JvmStatic
    fun isTablet(): Boolean {
        return (Resources.getSystem().configuration.screenLayout and
            Configuration.SCREENLAYOUT_SIZE_MASK) >= Configuration.SCREENLAYOUT_SIZE_LARGE
    }

    /**
     * Return whether device is emulator.
     *
     * @return {@code true}: yes<br>{@code false}: no
     */
    @JvmStatic
    fun isEmulator(context: Context): Boolean {
        val checkProperty = Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.lowercase().contains("vbox") ||
            Build.FINGERPRINT.lowercase().contains("test-keys") ||
            Build.MODEL.contains("google_sdk") ||
            Build.MODEL.contains("Emulator") ||
            Build.MODEL.contains("Android SDK built for x86") ||
            Build.MANUFACTURER.contains("Genymotion") ||
            Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic") ||
            "google_sdk" == Build.PRODUCT
        if (checkProperty) return true

        var operatorName = ""
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager?
        if (tm != null) {
            val name = tm.networkOperatorName
            if (name != null) {
                operatorName = name
            }
        }
        val checkOperatorName = operatorName.lowercase() == "android"
        if (checkOperatorName) return true

        val url = "tel:123456"
        val intent = Intent()
        intent.data = Uri.parse(url)
        intent.action = Intent.ACTION_DIAL
        val checkDial = intent.resolveActivity(context.packageManager) == null
        if (checkDial) return true
        return isEmulatorByCpu()
    }

    /**
     * Returns whether is emulator by check cpu info.
     * by function of {@link #readCpuInfo}, obtain the device cpu information.
     * then compare whether it is intel or amd (because intel and amd are generally not mobile phone cpu), to determine whether it is a real mobile phone
     *
     * @return {@code true}: yes<br>{@code false}: no
     */
    private fun isEmulatorByCpu(): Boolean {
        val cpuInfo = readCpuInfo()
        return cpuInfo.contains("intel") || cpuInfo.contains("amd")
    }

    /**
     * Return Cpu information
     *
     * @return Cpu info
     */
    @JvmStatic
    fun readCpuInfo(): String {
        var result = ""
        try {
            val args = arrayOf("/system/bin/cat", "/proc/cpuinfo")
            val process = ProcessBuilder(*args).start()
            val sb = StringBuilder()
            BufferedReader(InputStreamReader(process.inputStream, "utf-8")).use { responseReader ->
                while (true) {
                    val readLine = responseReader.readLine() ?: break
                    sb.append(readLine)
                }
            }
            result = sb.toString().lowercase()
        } catch (ignored: IOException) {
        }
        return result
    }

    /**
     * Whether user has enabled development settings.
     *
     * @return whether user has enabled development settings.
     */
    @JvmStatic
    @RequiresApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
    fun isDevelopmentSettingsEnabled(context: Context): Boolean {
        return Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
            0,
        ) > 0
    }
}
