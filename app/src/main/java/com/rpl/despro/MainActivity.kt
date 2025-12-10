package com.rpl.despro

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.tech.Ndef
import java.nio.charset.Charset
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    // ===== NFC =====
    private lateinit var nfcAdapter: NfcAdapter
    private lateinit var pendingIntent: PendingIntent
    private var isTagRead = false

    // ===== mDNS =====
    private var raspIP: String? = null
    private lateinit var nsdManager: NsdManager
    private lateinit var discoveryListener: NsdManager.DiscoveryListener
    private val allowedPis = setOf("mypi", "mypi2", "mypi3")

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

    private val resolveListener = object : NsdManager.ResolveListener {

        override fun onServiceResolved(service: NsdServiceInfo) {
            if (raspIP != null) return

            val host = service.host?.hostAddress ?: return
            val port = service.port

            raspIP = "http://$host:$port"

            showPopup("Raspberry Pi Found", raspIP ?: "Unknown address")
            Log.d("mDNS", "Resolved: $raspIP")

            //stopMdnsDiscovery()
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

    // ========== NFC HANDLING ==========
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        if (intent.action == NfcAdapter.ACTION_TAG_DISCOVERED ||
            intent.action == NfcAdapter.ACTION_NDEF_DISCOVERED)
        {
            val tag: Tag? = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            if (tag != null) {
                val text = readTextFromTag(tag)
                if (text != null) {
                    showPopup("Scan Berhasil", text)
                }
                else {
                    showPopup("No Text Found", "Please input you're message into the NFC tag")
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

                if (service.serviceType == "_http._tcp." && name in allowedPis) {
                    Log.d("mDNS", "Resolving: $name")
                    nsdManager.resolveService(service, resolveListener)
                }
            }


            override fun onServiceLost(service: NsdServiceInfo) {
                Log.d("mDNS", "Service lost: ${service.serviceName}")
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

        nsdManager.discoverServices("_http._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
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
        val base = raspIP ?: return
        val url = "$base/tes?nfcId=$nfcId"
        Thread {
            try {
                val result = java.net.URL(url).readText()

                runOnUiThread {
                    Toast.makeText(
                        this,
                        "$result",
                        Toast.LENGTH_LONG
                    ).show()
                }

            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "API unreachable",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }
}