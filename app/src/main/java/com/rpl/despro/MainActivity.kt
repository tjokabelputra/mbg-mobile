package com.rpl.despro

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
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
import com.rpl.despro.Util.extractMessage

class MainActivity : AppCompatActivity() {

    private lateinit var progressBar: ProgressBar

    // NFC
    private lateinit var nfcAdapter: NfcAdapter
    private lateinit var pendingIntent: PendingIntent
    private var isTagRead = false

    // MDNS and DDNS
    private var raspIP: String? = null
    private lateinit var nsdManager: NsdManager
    private lateinit var discoveryListener: NsdManager.DiscoveryListener

    private val discoveredPis = mutableMapOf<String, String>()
    private var selectedPi: String? = null

    //Popup
    private fun showPopup(title: String, message: String) {
        runOnUiThread {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("OK") { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
        }
    }

    // Dialog to Select RasPi
    private fun showPiSelector() {
        if (discoveredPis.isEmpty()) return
        if (raspIP != null) return

        val names = discoveredPis.keys.toTypedArray()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Select Raspberry Pi")
            .setItems(names) { _, which ->
                val name = names[which]
                val chosenUrl = discoveredPis[name]

                if (chosenUrl != null) {
                    raspIP = chosenUrl
                    selectedPi = name

                    showPopup(
                        "Connected",
                        "Connected to: $name\n$chosenUrl"
                    )

                    Log.d("PI_ACTIVE", "Active Pi = $selectedPi @ $raspIP")
                }
            }
            .setCancelable(false)
            .show()
    }

    private val resolveListener = object : NsdManager.ResolveListener {

        override fun onServiceResolved(service: NsdServiceInfo) {
            val host = service.host?.hostAddress ?: return
            val port = service.port
            val name = service.serviceName.lowercase()

            val url = "http://$host:$port/"
            discoveredPis[name] = url

            Log.d("mDNS", "Resolved: $name → $url")

            runOnUiThread {
                showPiSelector()
            }
        }

        override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
            Log.e("mDNS", "Resolve failed: $errorCode")
            showPopup("mDNS Error", "Resolve failed: $errorCode")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        progressBar = findViewById(R.id.pgLoading)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        if (!nfcAdapter.isEnabled) {
            Toast.makeText(this, "Enable NFC", Toast.LENGTH_LONG).show()
        }

        pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE
        )

        nsdManager = getSystemService(NSD_SERVICE) as NsdManager
        startMdnsDiscovery()
    }

    private fun showLoading() {
        runOnUiThread {
            progressBar.visibility = android.view.View.VISIBLE
        }
    }

    private fun hideLoading() {
        runOnUiThread {
            progressBar.visibility = android.view.View.GONE
        }
    }

    // NFC Handling
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        if (intent.action == NfcAdapter.ACTION_TAG_DISCOVERED || intent.action == NfcAdapter.ACTION_NDEF_DISCOVERED) {
            val tag: Tag? = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            if (tag != null) {
                val text = readTextFromTag(tag)
                if (text != null) {
                    callApi(text)
                }
                else {
                    showPopup(
                        "No Text Found",
                        "Please input you're message into the NFC tag"
                    )
                }
            }
        }
    }

    private fun readTextFromTag(tag: Tag): String? {
        val ndef = Ndef.get(tag) ?: return null

        val ndefMessage = ndef.cachedNdefMessage ?: return null
        val record = ndefMessage.records.firstOrNull() ?: return null

        if(record.tnf != NdefRecord.TNF_WELL_KNOWN) return null
        if(!record.type.contentEquals(NdefRecord.RTD_TEXT)) return null

        return Util.parseNdefText(record.payload)
    }

    override fun onResume() {
        super.onResume()
        isTagRead = false
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

    // ========== mDNS ==========
    private fun startMdnsDiscovery() {

        discoveryListener = object : NsdManager.DiscoveryListener {

            override fun onDiscoveryStarted(serviceType: String) {
                Log.d("mDNS", "Discovery started")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                val name = service.serviceName.lowercase()
                Log.d("mDNS", "Service found: $name")

                if (service.serviceType == "_mbg._tcp.") {
                    Log.d("mDNS", "Resolving: $name")
                    nsdManager.resolveService(service, resolveListener)
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.d("mDNS", "Service lost: ${service.serviceName}")
                val lostName = service.serviceName.lowercase()
                discoveredPis.remove(lostName)

                if (lostName == selectedPi) {
                    raspIP = null
                    selectedPi = null
                    showPopup("Raspberry Pi Lost", "Connection to $lostName lost. Please re-select device.")
                    showPiSelector()
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d("mDNS", "Discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e("mDNS", "Discovery start failed: $errorCode")
                nsdManager.stopServiceDiscovery(this)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e("mDNS", "Discovery stop failed: $errorCode")
                nsdManager.stopServiceDiscovery(this)
            }
        }

        nsdManager.discoverServices(
            "_mbg._tcp.",
            NsdManager.PROTOCOL_DNS_SD,
            discoveryListener
        )
    }

    private fun stopMdnsDiscovery() {
        try {
            nsdManager.stopServiceDiscovery(discoveryListener)
        } catch (e: Exception) {
            Log.w("mDNS", "Discovery already stopped")
        }
    }

    override fun onStop() {
        super.onStop()
        stopMdnsDiscovery()
    }

    private fun callApi(nfcId: String) {

        if (raspIP == null) {
            showPopup("No Raspberry Selected", "Please select a Raspberry Pi first.")
            return
        }

        val url = raspIP + "api/scan-dispatch"
        Log.d("API", "endpoint: ${url}")

        showLoading()

        Thread {
            try {
                val jsonBody = """
                    {
                      "batch-id": "$nfcId",
                      "item_name": "Melon"
                    }
                """.trimIndent()

                val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                connection.outputStream.use { os ->
                    os.write(jsonBody.toByteArray(Charsets.UTF_8))
                }

                val responseCode = connection.responseCode

                val result = if (responseCode in 200..299) {
                    connection.inputStream.bufferedReader().readText()
                }
                else {
                    connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                }

                runOnUiThread {
                    hideLoading()
                    val message = extractMessage(result)
                    showPopup("API Response", message)
                }

                connection.disconnect()
            }
            catch (e: Exception) {
                runOnUiThread {
                    hideLoading()
                    showPopup("API Error", e.message ?: "Unknown error")
                }
            }
        }.start()
    }
}