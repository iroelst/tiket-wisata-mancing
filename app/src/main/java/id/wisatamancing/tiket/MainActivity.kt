package id.wisatamancing.tiket

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Query
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.text.Normalizer
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import android.graphics.Bitmap

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private val executor = Executors.newSingleThreadExecutor()
    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var printerName: String = ""
    private val requestBluetoothPermission = 4102

    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    @Volatile private var signedProfile: JSONObject? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = WebViewClient()
        webView.addJavascriptInterface(AndroidPrinterBridge(), "AndroidPrinter")
        webView.addJavascriptInterface(AndroidFirebaseBridge(), "AndroidFirebase")
        webView.loadUrl("file:///android_asset/index.html")
    }


    private fun sendJs(functionName: String, payload: JSONObject) {
        val json = payload.toString()
        runOnUiThread {
            webView.evaluateJavascript("window.$functionName && window.$functionName($json);", null)
        }
    }

    inner class AndroidFirebaseBridge {
        @JavascriptInterface
        fun login(email: String, password: String) {
            auth.signInWithEmailAndPassword(email.trim(), password)
                .addOnSuccessListener { result ->
                    val user = result.user
                    if (user == null) {
                        sendJs("onAuthResult", JSONObject().put("ok", false).put("message", "Akun tidak ditemukan."))
                        return@addOnSuccessListener
                    }
                    db.collection("users").document(user.uid).get()
                        .addOnSuccessListener { doc ->
                            if (!doc.exists()) {
                                auth.signOut()
                                sendJs("onAuthResult", JSONObject().put("ok", false).put("message", "Profil pengguna belum dibuat di Firestore (users/${user.uid})."))
                                return@addOnSuccessListener
                            }
                            val role = doc.getString("role") ?: ""
                            val active = doc.getBoolean("active") ?: false
                            if (!active || (role != "admin" && role != "petugas")) {
                                auth.signOut()
                                sendJs("onAuthResult", JSONObject().put("ok", false).put("message", "Akun belum aktif atau role tidak valid."))
                                return@addOnSuccessListener
                            }
                            val profile = JSONObject()
                                .put("uid", user.uid)
                                .put("email", user.email ?: email)
                                .put("name", doc.getString("name") ?: user.email ?: email)
                                .put("role", role)
                                .put("active", active)
                            signedProfile = profile
                            sendJs("onAuthResult", JSONObject().put("ok", true).put("profile", profile))
                        }
                        .addOnFailureListener { e ->
                            auth.signOut()
                            sendJs("onAuthResult", JSONObject().put("ok", false).put("message", "Gagal membaca profil Firestore: ${e.localizedMessage ?: "akses ditolak"}"))
                        }
                }
                .addOnFailureListener { e ->
                    sendJs("onAuthResult", JSONObject().put("ok", false).put("message", e.localizedMessage ?: "Login gagal. Periksa email dan password."))
                }
        }

        @JavascriptInterface
        fun logout() {
            auth.signOut()
            signedProfile = null
            sendJs("onLogoutResult", JSONObject().put("ok", true))
        }

        @JavascriptInterface
        fun saveSale(json: String) {
            val user = auth.currentUser
            val profile = signedProfile
            if (user == null || profile == null) {
                sendJs("onCloudSaveResult", JSONObject().put("ok", false).put("message", "Sesi login tidak ditemukan. Silakan keluar lalu login kembali."))
                return
            }
            try {
                val sale = JSONObject(json)
                val trxId = sale.optString("trxId")
                if (trxId.isBlank()) throw IllegalArgumentException("Nomor transaksi kosong.")
                sale.put("uid", user.uid)
                sale.put("userEmail", user.email ?: "")
                sale.put("cashier", profile.optString("name", user.email ?: "Petugas"))
                sale.put("createdAt", System.currentTimeMillis())
                val data = saleToMap(sale)
                data["serverCreatedAt"] = FieldValue.serverTimestamp()
                db.collection("sales").document(trxId).set(data)
                    .addOnSuccessListener {
                        sendJs("onCloudSaveResult", JSONObject().put("ok", true).put("trxId", trxId))
                    }
                    .addOnFailureListener { e ->
                        sendJs("onCloudSaveResult", JSONObject().put("ok", false).put("message", e.localizedMessage ?: "Firestore menolak penyimpanan."))
                    }
            } catch (e: Exception) {
                sendJs("onCloudSaveResult", JSONObject().put("ok", false).put("message", e.localizedMessage ?: "Format transaksi tidak valid."))
            }
        }

        @JavascriptInterface
        fun loadConfig() {
            val user = auth.currentUser
            val profile = signedProfile
            if (user == null || profile == null) {
                sendJs("onCloudConfig", JSONObject().put("ok", false).put("message", "Belum login ke Firebase."))
                return
            }
            db.collection("settings").document("main").get()
                .addOnSuccessListener { settingsDoc ->
                    db.collection("ticket_types").get()
                        .addOnSuccessListener { typeSnapshot ->
                            val settingsObj = if (settingsDoc.exists()) JSONObject(settingsDoc.data ?: emptyMap<String, Any>()) else JSONObject()
                            val typesArray = JSONArray()
                            typeSnapshot.documents.forEach { doc ->
                                val item = JSONObject(doc.data ?: emptyMap<String, Any>())
                                if (!item.has("id")) item.put("id", doc.id)
                                typesArray.put(item)
                            }
                            sendJs("onCloudConfig", JSONObject()
                                .put("ok", true)
                                .put("settingsExists", settingsDoc.exists())
                                .put("typesExist", typeSnapshot.documents.isNotEmpty())
                                .put("settings", settingsObj)
                                .put("types", typesArray))
                        }
                        .addOnFailureListener { e ->
                            sendJs("onCloudConfig", JSONObject().put("ok", false).put("message", e.localizedMessage ?: "Gagal memuat jenis tiket."))
                        }
                }
                .addOnFailureListener { e ->
                    sendJs("onCloudConfig", JSONObject().put("ok", false).put("message", e.localizedMessage ?: "Gagal memuat identitas wisata."))
                }
        }

        @JavascriptInterface
        fun saveConfig(settingsJson: String, typesJson: String) {
            val user = auth.currentUser
            val profile = signedProfile
            if (user == null || profile == null || profile.optString("role") != "admin") {
                sendJs("onCloudConfigSaveResult", JSONObject().put("ok", false).put("message", "Hanya Admin yang boleh mengubah pengaturan."))
                return
            }
            try {
                val settingsObject = JSONObject(settingsJson)
                val typesArray = JSONArray(typesJson)
                val settingsMap = saleToMap(settingsObject)
                settingsMap["updatedBy"] = user.uid
                settingsMap["updatedAt"] = FieldValue.serverTimestamp()

                db.collection("ticket_types").get()
                    .addOnSuccessListener { existing ->
                        val batch = db.batch()
                        batch.set(db.collection("settings").document("main"), settingsMap)
                        val keepIds = mutableSetOf<String>()
                        for (i in 0 until typesArray.length()) {
                            val item = typesArray.getJSONObject(i)
                            val typeId = item.optString("id").trim()
                            if (typeId.isBlank()) continue
                            keepIds.add(typeId)
                            val typeMap = saleToMap(item)
                            typeMap["updatedBy"] = user.uid
                            typeMap["updatedAt"] = FieldValue.serverTimestamp()
                            batch.set(db.collection("ticket_types").document(typeId), typeMap)
                        }
                        existing.documents.forEach { doc ->
                            if (!keepIds.contains(doc.id)) batch.delete(doc.reference)
                        }
                        batch.commit()
                            .addOnSuccessListener {
                                sendJs("onCloudConfigSaveResult", JSONObject().put("ok", true).put("message", "Pengaturan berhasil disimpan ke Firestore."))
                            }
                            .addOnFailureListener { e ->
                                sendJs("onCloudConfigSaveResult", JSONObject().put("ok", false).put("message", e.localizedMessage ?: "Firestore menolak perubahan."))
                            }
                    }
                    .addOnFailureListener { e ->
                        sendJs("onCloudConfigSaveResult", JSONObject().put("ok", false).put("message", e.localizedMessage ?: "Gagal memeriksa jenis tiket."))
                    }
            } catch (e: Exception) {
                sendJs("onCloudConfigSaveResult", JSONObject().put("ok", false).put("message", e.localizedMessage ?: "Format pengaturan tidak valid."))
            }
        }

        @JavascriptInterface
        fun loadStaff() {
            val user = auth.currentUser
            val profile = signedProfile
            if (user == null || profile == null || profile.optString("role") != "admin") {
                sendJs("onStaffList", JSONObject().put("ok", false).put("message", "Hanya Admin yang dapat mengelola petugas."))
                return
            }
            db.collection("users").get()
                .addOnSuccessListener { snapshot ->
                    val arr = JSONArray()
                    snapshot.documents.forEach { doc ->
                        val role = doc.getString("role") ?: ""
                        if (role == "petugas") {
                            arr.put(JSONObject()
                                .put("uid", doc.id)
                                .put("name", doc.getString("name") ?: "")
                                .put("email", doc.getString("email") ?: "")
                                .put("role", role)
                                .put("active", doc.getBoolean("active") ?: false))
                        }
                    }
                    sendJs("onStaffList", JSONObject().put("ok", true).put("staff", arr))
                }
                .addOnFailureListener { e ->
                    sendJs("onStaffList", JSONObject().put("ok", false).put("message", e.localizedMessage ?: "Gagal memuat daftar petugas."))
                }
        }

        @JavascriptInterface
        fun createStaff(name: String, email: String, password: String) {
            val profile = signedProfile
            if (auth.currentUser == null || profile == null || profile.optString("role") != "admin") {
                sendJs("onStaffActionResult", JSONObject().put("ok", false).put("message", "Hanya Admin yang dapat menambahkan petugas."))
                return
            }
            val cleanName = name.trim()
            val cleanEmail = email.trim()
            if (cleanName.isBlank() || cleanEmail.isBlank() || password.length < 6) {
                sendJs("onStaffActionResult", JSONObject().put("ok", false).put("message", "Nama dan email wajib diisi. Password minimal 6 karakter."))
                return
            }
            try {
                val secondaryApp = try {
                    FirebaseApp.getInstance("staffCreator")
                } catch (_: IllegalStateException) {
                    FirebaseApp.initializeApp(applicationContext, FirebaseApp.getInstance().options, "staffCreator")
                        ?: throw IllegalStateException("Tidak dapat menyiapkan Firebase untuk pembuatan akun.")
                }
                val secondaryAuth = FirebaseAuth.getInstance(secondaryApp)
                secondaryAuth.createUserWithEmailAndPassword(cleanEmail, password)
                    .addOnSuccessListener { result ->
                        val newUser = result.user
                        if (newUser == null) {
                            sendJs("onStaffActionResult", JSONObject().put("ok", false).put("message", "Akun dibuat tetapi UID tidak terbaca."))
                            return@addOnSuccessListener
                        }
                        val userData = hashMapOf<String, Any>(
                            "name" to cleanName,
                            "email" to cleanEmail,
                            "role" to "petugas",
                            "active" to true,
                            "createdAt" to FieldValue.serverTimestamp(),
                            "createdBy" to (auth.currentUser?.uid ?: "")
                        )
                        db.collection("users").document(newUser.uid).set(userData)
                            .addOnSuccessListener {
                                secondaryAuth.signOut()
                                sendJs("onStaffActionResult", JSONObject().put("ok", true).put("message", "Akun petugas berhasil dibuat."))
                                loadStaff()
                            }
                            .addOnFailureListener { e ->
                                secondaryAuth.signOut()
                                sendJs("onStaffActionResult", JSONObject().put("ok", false).put("message", "Akun Auth berhasil dibuat, tetapi profil Firestore gagal disimpan: ${e.localizedMessage ?: "akses ditolak"}. Hapus akun tanpa profil dari Authentication atau ulangi setelah Rules diperbaiki."))
                            }
                    }
                    .addOnFailureListener { e ->
                        secondaryAuth.signOut()
                        sendJs("onStaffActionResult", JSONObject().put("ok", false).put("message", e.localizedMessage ?: "Gagal membuat akun petugas."))
                    }
            } catch (e: Exception) {
                sendJs("onStaffActionResult", JSONObject().put("ok", false).put("message", e.localizedMessage ?: "Gagal menyiapkan Firebase Auth."))
            }
        }

        @JavascriptInterface
        fun updateStaff(uid: String, name: String, active: Boolean) {
            val profile = signedProfile
            if (auth.currentUser == null || profile == null || profile.optString("role") != "admin") {
                sendJs("onStaffActionResult", JSONObject().put("ok", false).put("message", "Hanya Admin yang dapat mengubah petugas."))
                return
            }
            if (uid.isBlank() || name.trim().isBlank()) {
                sendJs("onStaffActionResult", JSONObject().put("ok", false).put("message", "Nama petugas tidak boleh kosong."))
                return
            }
            db.collection("users").document(uid)
                .update(mapOf("name" to name.trim(), "active" to active, "updatedAt" to FieldValue.serverTimestamp()))
                .addOnSuccessListener {
                    sendJs("onStaffActionResult", JSONObject().put("ok", true).put("message", if (active) "Petugas diaktifkan." else "Petugas dinonaktifkan."))
                    loadStaff()
                }
                .addOnFailureListener { e ->
                    sendJs("onStaffActionResult", JSONObject().put("ok", false).put("message", e.localizedMessage ?: "Gagal mengubah petugas."))
                }
        }

        @JavascriptInterface
        fun sendStaffPasswordReset(email: String) {
            val profile = signedProfile
            if (auth.currentUser == null || profile == null || profile.optString("role") != "admin") {
                sendJs("onStaffActionResult", JSONObject().put("ok", false).put("message", "Hanya Admin yang dapat mengirim reset password."))
                return
            }
            auth.sendPasswordResetEmail(email.trim())
                .addOnSuccessListener {
                    sendJs("onStaffActionResult", JSONObject().put("ok", true).put("message", "Email reset password dikirim ke ${email.trim()}."))
                }
                .addOnFailureListener { e ->
                    sendJs("onStaffActionResult", JSONObject().put("ok", false).put("message", e.localizedMessage ?: "Gagal mengirim email reset password."))
                }
        }

        @JavascriptInterface
        fun loadSales() {
            val user = auth.currentUser
            val profile = signedProfile
            if (user == null || profile == null) {
                sendJs("onCloudSales", JSONObject().put("ok", false).put("message", "Belum login ke Firebase."))
                return
            }
            var query: Query = db.collection("sales")
            if (profile.optString("role") != "admin") {
                query = query.whereEqualTo("uid", user.uid)
            }
            query.get()
                .addOnSuccessListener { snapshot ->
                    val arr = JSONArray()
                    snapshot.documents.forEach { doc ->
                        val map = doc.data ?: emptyMap<String, Any>()
                        val obj = JSONObject(map)
                        if (!obj.has("trxId")) obj.put("trxId", doc.id)
                        arr.put(obj)
                    }
                    sendJs("onCloudSales", JSONObject().put("ok", true).put("sales", arr))
                }
                .addOnFailureListener { e ->
                    sendJs("onCloudSales", JSONObject().put("ok", false).put("message", e.localizedMessage ?: "Gagal membaca transaksi Firestore."))
                }
        }
    }

    private fun saleToMap(json: JSONObject): HashMap<String, Any?> {
        val result = HashMap<String, Any?>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            result[key] = jsonValue(json.get(key))
        }
        return result
    }

    private fun jsonValue(value: Any?): Any? = when (value) {
        JSONObject.NULL -> null
        is JSONObject -> saleToMap(value)
        is JSONArray -> (0 until value.length()).map { jsonValue(value.get(it)) }
        else -> value
    }

    private fun ensureBluetoothPermission(onGranted: (() -> Unit)? = null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), requestBluetoothPermission)
        } else onGranted?.invoke()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == requestBluetoothPermission && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Izin Bluetooth diberikan", Toast.LENGTH_SHORT).show()
            showPairedPrinters()
        } else if (requestCode == requestBluetoothPermission) {
            Toast.makeText(this, "Izin Bluetooth diperlukan untuk printer", Toast.LENGTH_LONG).show()
        }
    }

    private fun status(message: String, connected: Boolean) {
        val safe = JSONObject.quote(message)
        runOnUiThread { webView.evaluateJavascript("window.onAndroidPrinterStatus && window.onAndroidPrinterStatus($safe, $connected);", null) }
    }

    inner class AndroidPrinterBridge {
        @JavascriptInterface fun choosePrinter() {
            runOnUiThread { ensureBluetoothPermission { showPairedPrinters() } }
        }
        @JavascriptInterface fun printTickets(json: String) {
            executor.execute {
                try {
                    val active = socket
                    if (active == null || !active.isConnected) throw IllegalStateException("Printer belum tersambung. Pilih printer terlebih dahulu.")
                    val tickets = JSONArray(json)
                    for (i in 0 until tickets.length()) printOneTicket(active.outputStream, tickets.getJSONObject(i))
                    active.outputStream.write(byteArrayOf(0x1D, 0x56, 0x00)) // partial cut; ignored by printers without cutter
                    active.outputStream.flush()
                    status("Berhasil mengirim ${tickets.length()} tiket ke $printerName", true)
                } catch (e: Exception) {
                    status("Gagal mencetak: ${e.message ?: "kesalahan Bluetooth"}", false)
                }
            }
        }
    }

    private fun showPairedPrinters() {
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) { status("HP tidak memiliki Bluetooth", false); return }
            if (!adapter.isEnabled) { status("Aktifkan Bluetooth HP terlebih dahulu", false); return }
            val devices = adapter.bondedDevices?.toList().orEmpty()
            if (devices.isEmpty()) {
                AlertDialog.Builder(this).setTitle("Belum ada printer terpasang")
                    .setMessage("Pasangkan RPP02N melalui Pengaturan > Bluetooth, lalu pilih tombol ini kembali.")
                    .setPositiveButton("Mengerti", null).show()
                return
            }
            val labels = devices.map { "${it.name ?: "Perangkat Bluetooth"}\n${it.address}" }.toTypedArray()
            AlertDialog.Builder(this).setTitle("Pilih printer Bluetooth")
                .setItems(labels) { _, which -> connectPrinter(devices[which]) }
                .setNegativeButton("Batal", null).show()
        } catch (e: SecurityException) { status("Izin Bluetooth belum diberikan", false) }
    }

    private fun connectPrinter(device: BluetoothDevice) {
        executor.execute {
            try {
                socket?.close()
                val s = device.createRfcommSocketToServiceRecord(sppUuid)
                s.connect()
                socket = s
                printerName = device.name ?: "RPP02N"
                status("Terhubung ke $printerName", true)
            } catch (e: Exception) {
                try { socket?.close() } catch (_: Exception) {}
                socket = null
                status("Tidak dapat terhubung ke ${device.name}: ${e.message}", false)
            }
        }
    }

    private fun printOneTicket(out: OutputStream, t: JSONObject) {
        val name = t.optString("typeName", "Tiket Masuk")
        val ticketNo = t.optString("ticketNo", "-")
        val dateText = t.optString("dateText", "")
        val price = NumberFormat.getCurrencyInstance(Locale("id", "ID"))
            .format(t.optDouble("price", 0.0)).replace(",00", "")
        val settingsName = t.optString("brand", "WISATA MANCING")
        val address = t.optString("address", "Tempat wisata & pemancingan")
        val footer = t.optString("footer", "Terima kasih telah berkunjung")

        out.write(byteArrayOf(0x1B, 0x40)) // initialize
        out.write(byteArrayOf(0x1B, 0x61, 0x01)) // center

        // Header: nama wisata dan alamat saja
        out.write(byteArrayOf(0x1B, 0x21, 0x18))
        line(out, settingsName)
        out.write(byteArrayOf(0x1B, 0x21, 0x00))
        line(out, address)
        line(out, "--------------------------------")

        // Nama jenis tiket tepat di atas nomor tiket
        out.write(byteArrayOf(0x1B, 0x21, 0x10))
        line(out, name)
        out.write(byteArrayOf(0x1B, 0x21, 0x00))
        line(out, ticketNo)
        line(out, "--------------------------------")

        // Informasi tiket
        out.write(byteArrayOf(0x1B, 0x61, 0x00))
        line(out, "Tanggal : $dateText")
        line(out, "Harga   : $price")

        // QR Code
        out.write(byteArrayOf(0x1B, 0x61, 0x01))
        val qrValue = QRCodeWriter().encode(ticketNo, BarcodeFormat.QR_CODE, 220, 220)
        val bitmap = Bitmap.createBitmap(220, 220, Bitmap.Config.ARGB_8888)
        for (y in 0 until 220) {
            for (x in 0 until 220) {
                bitmap.setPixel(
                    x, y,
                    if (qrValue[x, y]) android.graphics.Color.BLACK
                    else android.graphics.Color.WHITE
                )
            }
        }
        printBitmap(out, bitmap)
        bitmap.recycle()

        line(out, "Berlaku untuk satu orang")
        line(out, footer)
        line(out, "\n\n")
    }

    private fun line(out: OutputStream, text: String) {
        val ascii = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").replace("–", "-")
        out.write(ascii.toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0x0A))
    }

    private fun printBitmap(out: OutputStream, bitmap: Bitmap) {
        val widthBytes = (bitmap.width + 7) / 8
        val height = bitmap.height
        out.write(byteArrayOf(0x1D, 0x76, 0x30, 0x00, (widthBytes and 0xFF).toByte(), (widthBytes shr 8).toByte(), (height and 0xFF).toByte(), (height shr 8).toByte()))
        val bytes = ByteArray(widthBytes * height)
        for (y in 0 until height) for (x in 0 until bitmap.width) {
            if (bitmap.getPixel(x, y) == android.graphics.Color.BLACK) {
                val index = y * widthBytes + x / 8
                bytes[index] = (bytes[index].toInt() or (0x80 shr (x % 8))).toByte()
            }
        }
        out.write(bytes)
        out.write(byteArrayOf(0x0A))
    }

    override fun onDestroy() {
        try { socket?.close() } catch (_: Exception) {}
        executor.shutdownNow()
        webView.removeJavascriptInterface("AndroidPrinter")
        super.onDestroy()
    }
}
