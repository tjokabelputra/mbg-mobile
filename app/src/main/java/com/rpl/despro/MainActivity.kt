package com.rpl.despro

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.NdefRecord
import android.nfc.tech.Ndef
import android.os.Bundle
import android.util.Log
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.rpl.despro.Util.extractMessage

class MainActivity : AppCompatActivity() {

    private lateinit var nfcAdapter: NfcAdapter
    private lateinit var pendingIntent: PendingIntent
    private lateinit var progressBar: ProgressBar
    private lateinit var fabNetwork: FloatingActionButton

    private var raspIP: String? = null
    private var selectedPi: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.topAppBar)
        setSupportActionBar(toolbar)

        setupEdgeToEdge()

        progressBar = findViewById(R.id.pgLoading)
        fabNetwork = findViewById(R.id.fabNetwork)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (!nfcAdapter.isEnabled) {
            Toast.makeText(this, "Enable NFC", Toast.LENGTH_LONG).show()
        }

        pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE
        )

        fabNetwork.setOnClickListener {
            startActivity(Intent(this, NetworkActivity::class.java))
        }

        loadRaspberryFromStorage()
    }

    override fun onResume() {
        super.onResume()
        loadRaspberryFromStorage()

        val filters = arrayOf(
            IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED),
            IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED)
        )
        nfcAdapter.enableForegroundDispatch(this, pendingIntent, filters, null)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter.disableForegroundDispatch(this)
    }

    private fun setupEdgeToEdge() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, 0, bars.right, bars.bottom)
            findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.topAppBar)
                ?.setPadding(0, bars.top, 0, 0)
            insets
        }
    }

    // Storage
    private fun loadRaspberryFromStorage() {
        val prefs = getSharedPreferences("raspberry_config", MODE_PRIVATE)
        raspIP = prefs.getString("PI_URL", null)
        selectedPi = prefs.getString("PI_NAME", null)

        Log.d("LOCAL", "Loaded Pi: $selectedPi @ $raspIP")
    }

    // NFC
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        if (intent.action == NfcAdapter.ACTION_TAG_DISCOVERED ||
            intent.action == NfcAdapter.ACTION_NDEF_DISCOVERED) {

            val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG) ?: return

            val tagId = tag.id.joinToString("") { "%02X".format(it) }
            val text = readTextFromTag(tag) ?: return

            sendToApi(tagId, text)
        }
    }

    private fun readTextFromTag(tag: Tag): String? {
        val ndef = Ndef.get(tag) ?: return null
        val message = ndef.cachedNdefMessage ?: return null
        val record = message.records.firstOrNull() ?: return null

        if (record.tnf != NdefRecord.TNF_WELL_KNOWN) return null
        if (!record.type.contentEquals(NdefRecord.RTD_TEXT)) return null

        return Util.parseNdefText(record.payload)
    }

    // API
    private fun sendToApi(nfcId: String, item: String) {

        if (raspIP == null) {
            showPopup("No Raspberry Pi", "Open Network screen and connect first")
            return
        }

        val url = raspIP + "api/scan-dispatch"
        Log.d("API", url)
        showLoading()

        Thread {
            try {
                val json = """
                    {
                      "nfc_id": "$nfcId",
                      "item_name": "$item"
                    }
                """.trimIndent()

                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true

                conn.outputStream.use { it.write(json.toByteArray()) }

                val response = if (conn.responseCode in 200..299)
                    conn.inputStream.bufferedReader().readText()
                else
                    conn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"

                runOnUiThread {
                    hideLoading()
                    showPopup("Response", extractMessage(response))
                }

                conn.disconnect()

            } catch (e: Exception) {
                runOnUiThread {
                    hideLoading()
                    showPopup("Error", e.message ?: "Unknown")
                }
            }
        }.start()
    }

    private fun showLoading() { progressBar.visibility = ProgressBar.VISIBLE }
    private fun hideLoading() { progressBar.visibility = ProgressBar.GONE }

    private fun showPopup(title: String, msg: String) {
        runOnUiThread {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .show()
        }
    }
}