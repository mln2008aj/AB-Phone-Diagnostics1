package com.abs.phonesolutions.core

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

data class FoundPc(val name: String, val host: String)
data class UploadResult(val ok: Boolean, val error: String? = null)

/**
 * Talks to the AB's Phone Solutions PC program.
 * USB: the PC program runs "adb reverse", so the PC is reachable at 127.0.0.1 on the phone.
 * Wi-Fi: the phone finds the PC with a broadcast on the local network.
 * All calls block: run them on Dispatchers.IO.
 */
class PcLink(private val ctx: Context) {
    companion object {
        const val PORT = 8765
        const val DISCOVERY_PORT = 8767
        const val USB_HOST = "127.0.0.1"
    }

    private val prefs = ctx.getSharedPreferences("pc_link", Context.MODE_PRIVATE)

    var host: String
        get() = prefs.getString("host", "") ?: ""
        set(v) { prefs.edit().putString("host", v).apply() }
    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(v) { prefs.edit().putString("token", v).apply() }
    var pcName: String
        get() = prefs.getString("pcName", "") ?: ""
        set(v) { prefs.edit().putString("pcName", v).apply() }
    /** "usb" or "wifi" */
    var via: String
        get() = prefs.getString("via", "wifi") ?: "wifi"
        set(v) { prefs.edit().putString("via", v).apply() }
    var wifiHost: String
        get() = prefs.getString("wifiHost", "") ?: ""
        set(v) { prefs.edit().putString("wifiHost", v).apply() }

    val deviceName: String get() = (Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + Build.MODEL).trim()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun call(h: String, method: String, path: String, body: JSONObject? = null, timeout: Int = 8000): Pair<Int, JSONObject?> {
        val c = URL("http://$h:$PORT$path").openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = timeout
            c.readTimeout = timeout
            if (token.isNotEmpty()) c.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                val b = body.toString().toByteArray()
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.setFixedLengthStreamingMode(b.size)
                c.outputStream.use { it.write(b) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val json = try { JSONObject(text) } catch (e: Exception) { null }
            return code to json
        } finally {
            c.disconnect()
        }
    }

    /** Is the PC program answering at this address? Returns its name or null. */
    fun probe(h: String): String? = try {
        val (code, j) = call(h, "GET", "/api/info", timeout = 2500)
        if (code == 200 && j != null && j.optString("app") == "ABPS-PC") j.optString("pcName", "PC") else null
    } catch (e: Exception) {
        null
    }

    fun reachable(): Boolean = host.isNotEmpty() && probe(host) != null

    /** Looks for PCs running the program on the same Wi-Fi (about 2.5 seconds). */
    fun discover(): List<FoundPc> {
        val found = LinkedHashMap<String, FoundPc>()
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("abps-discovery")
        lock.setReferenceCounted(false)
        try {
            lock.acquire()
            DatagramSocket().use { s ->
                s.broadcast = true
                s.soTimeout = 300
                val msg = "ABPS_DISCOVER".toByteArray()
                val targets = ArrayList<InetAddress>()
                targets.add(InetAddress.getByName("255.255.255.255"))
                subnetBroadcast(wifi)?.let { targets.add(it) }
                val end = System.currentTimeMillis() + 2500
                var lastSend = 0L
                while (System.currentTimeMillis() < end) {
                    if (System.currentTimeMillis() - lastSend > 700) {
                        for (t in targets) {
                            try { s.send(DatagramPacket(msg, msg.size, t, DISCOVERY_PORT)) } catch (e: Exception) { }
                        }
                        lastSend = System.currentTimeMillis()
                    }
                    try {
                        val buf = ByteArray(512)
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        val j = JSONObject(String(p.data, 0, p.length))
                        val h = p.address?.hostAddress
                        if (j.optString("app") == "ABPS-PC" && h != null) found[h] = FoundPc(j.optString("pcName", "PC"), h)
                    } catch (e: SocketTimeoutException) {
                    } catch (e: Exception) {
                    }
                }
            }
        } catch (e: Exception) {
        } finally {
            try { lock.release() } catch (e: Exception) { }
        }
        return found.values.toList()
    }

    @Suppress("DEPRECATION")
    private fun subnetBroadcast(wifi: WifiManager): InetAddress? = try {
        val d = wifi.dhcpInfo
        if (d == null || d.ipAddress == 0) null else {
            val b = (d.ipAddress and d.netmask) or d.netmask.inv()
            InetAddress.getByAddress(ByteArray(4) { i -> (b shr (i * 8) and 0xff).toByte() })
        }
    } catch (e: Exception) {
        null
    }

    /** Pairs with the PC using the code shown on its screen. Returns null on success, else a message. */
    fun pair(h: String, code: String, newVia: String): String? = try {
        token = ""
        val (status, j) = call(h, "POST", "/api/pair", JSONObject().put("code", code.filter { it.isDigit() }).put("device", deviceName))
        if (status == 200 && j != null && j.optString("token").isNotEmpty()) {
            token = j.getString("token")
            pcName = j.optString("pcName", "PC")
            host = h
            via = newVia
            if (newVia == "wifi") wifiHost = h
            null
        } else {
            j?.optString("error")?.takeIf { it.isNotEmpty() } ?: "The PC did not accept the code"
        }
    } catch (e: Exception) {
        "Could not reach the PC. Is the PC program open?"
    }

    fun forget() {
        prefs.edit().clear().apply()
    }

    /** Which of these files does the PC still need? Returns their positions in the list. */
    fun check(files: List<PhoneFile>): List<Int> {
        val need = ArrayList<Int>()
        var start = 0
        while (start < files.size) {
            val end = minOf(files.size, start + 400)
            val arr = JSONArray()
            for (i in start until end) {
                val f = files[i]
                arr.put(JSONObject().put("path", f.rel).put("size", f.size).put("mtime", f.mtime))
            }
            val (code, j) = call(host, "POST", "/api/check", JSONObject().put("files", arr), timeout = 60000)
            if (code == 401) throw IllegalStateException("The PC no longer knows this phone. Pair again.")
            if (code != 200 || j == null) throw IllegalStateException("The PC did not answer")
            val n = j.getJSONArray("need")
            for (k in 0 until n.length()) need.add(start + n.getInt(k))
            start = end
        }
        return need
    }

    fun session(event: String, files: Int = 0, bytes: Long = 0, mode: String = "copy") {
        try {
            call(host, "POST", "/api/session", JSONObject().put("event", event).put("files", files).put("bytes", bytes).put("mode", mode))
        } catch (e: Exception) {
        }
    }

    /**
     * Sends one file. The PC computes SHA-256 of what it saved; the phone computes it of what it
     * sent. Only an exact match counts as a verified copy.
     */
    fun upload(f: PhoneFile, onBytes: (Long) -> Unit): UploadResult {
        val c = URL("http://$host:$PORT/api/upload?path=" + enc(f.rel) + "&mtime=" + f.mtime).openConnection() as HttpURLConnection
        return try {
            c.requestMethod = "PUT"
            c.doOutput = true
            c.connectTimeout = 5000
            c.readTimeout = 120000
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("Content-Type", "application/octet-stream")
            c.setFixedLengthStreamingMode(f.size)
            val md = MessageDigest.getInstance("SHA-256")
            var sent = 0L
            FileInputStream(f.file).use { input ->
                c.outputStream.use { out ->
                    val buf = ByteArray(512 * 1024)
                    while (sent < f.size) {
                        val n = input.read(buf, 0, minOf(buf.size.toLong(), f.size - sent).toInt())
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        sent += n
                        onBytes(n.toLong())
                    }
                }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            val j = try { JSONObject(text) } catch (e: Exception) { null }
            when {
                code == 507 -> UploadResult(false, "The PC disk is full")
                code !in 200..299 -> UploadResult(false, j?.optString("error") ?: "PC refused the file ($code)")
                sent != f.size -> UploadResult(false, "File changed while sending")
                j?.optString("sha256") == PhoneFiles.hex(md.digest()) -> UploadResult(true)
                else -> UploadResult(false, "Copy on PC did not match")
            }
        } catch (e: Exception) {
            UploadResult(false, e.message ?: "Connection lost")
        } finally {
            c.disconnect()
        }
    }

    /** For a file the PC already had: does the PC copy match the phone copy exactly? */
    fun verify(f: PhoneFile): Boolean = try {
        val (code, j) = call(host, "GET", "/api/verify?path=" + enc(f.rel), timeout = 120000)
        code == 200 && j != null && j.optBoolean("exists") && j.optLong("size") == f.size &&
            j.optString("sha256") == PhoneFiles.sha256(f.file)
    } catch (e: Exception) {
        false
    }
}
