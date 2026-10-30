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
        webView.loadUrl("file:///android_asset/index.html")
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
