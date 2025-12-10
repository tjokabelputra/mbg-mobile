package com.rpl.despro

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton

class NetworkActivity : AppCompatActivity() {

    private lateinit var nsdManager: NsdManager
    private lateinit var discoveryListener: NsdManager.DiscoveryListener
    private lateinit var fabDisconnect: FloatingActionButton

    private val discoveredPis = mutableMapOf<String, String>()
    private val resolvingServices = mutableSetOf<String>()

    private var isDiscovering = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_network)
        setupEdgeToEdge()

        fabDisconnect = findViewById(R.id.fabDisconnect)
        fabDisconnect.setOnClickListener { showDisconnectConfirm() }

        val toolbar = findViewById<MaterialToolbar>(R.id.topAppBar)
        setSupportActionBar(toolbar)

        nsdManager = getSystemService(NSD_SERVICE) as NsdManager
        loadSavedRaspberry()
    }

    override fun onResume() {
        super.onResume()
        loadSavedRaspberry()
        startDiscovery()
    }

    override fun onPause() {
        super.onPause()
        stopDiscovery()
    }

    //Disconnect
    private fun showDisconnectConfirm() {

        val prefs = getSharedPreferences("raspberry_config", MODE_PRIVATE)
        val name = prefs.getString("PI_NAME", null)

        if (name == null) {
            Toast.makeText(this, "No Raspberry connected", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Disconnect Raspberry Pi")
            .setMessage("Disconnect from $name?")
            .setPositiveButton("Disconnect") { _, _ -> disconnectRaspberry() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun disconnectRaspberry() {

        stopDiscovery()

        getSharedPreferences("raspberry_config", MODE_PRIVATE)
            .edit()
            .clear()
            .apply()

        findViewById<TextView>(R.id.tvNameContent).text = "No device"
        findViewById<TextView>(R.id.tvIPContent).text = "-"

        discoveredPis.clear()
        resolvingServices.clear()

        invalidateOptionsMenu()
        Toast.makeText(this, "Disconnected", Toast.LENGTH_SHORT).show()

        startDiscovery()
    }

    // Menu
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_network, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        menu?.findItem(R.id.menu_connect_pi)?.isVisible = true
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {

        return when (item.itemId) {
            R.id.menu_connect_pi -> {
                showPiSelector()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }


    // UI
    private fun setupEdgeToEdge() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun loadSavedRaspberry() {

        val prefs = getSharedPreferences("raspberry_config", MODE_PRIVATE)
        val name = prefs.getString("PI_NAME", null)
        val url = prefs.getString("PI_URL", null)

        if (name != null && url != null) {
            findViewById<TextView>(R.id.tvNameContent).text = name
            findViewById<TextView>(R.id.tvIPContent).text = url
        } else {
            showScanningState()
        }
    }

    private fun showScanningState() {
        findViewById<TextView>(R.id.tvNameContent).text = "Scanning network..."
        findViewById<TextView>(R.id.tvIPContent).text = "-"
    }

    private fun saveRaspberry(name: String, url: String) {

        getSharedPreferences("raspberry_config", MODE_PRIVATE)
            .edit()
            .putString("PI_NAME", name)
            .putString("PI_URL", url)
            .apply()

        findViewById<TextView>(R.id.tvNameContent).text = name
        findViewById<TextView>(R.id.tvIPContent).text = url

        Toast.makeText(this, "Connected to $name", Toast.LENGTH_SHORT).show()
    }

    private fun showPiSelector() {

        if (discoveredPis.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Raspberry Pi")
                .setMessage("No Raspberry Pi found on network")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val names = discoveredPis.keys.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select Raspberry Pi")
            .setItems(names) { _, which ->

                val name = names[which]
                val url = discoveredPis[name] ?: return@setItems

                saveRaspberry(name, url)
            }
            .show()
    }

    // Discovery Controll
    private fun startDiscovery() {

        if (isDiscovering) return
        isDiscovering = true

        discoveryListener = object : NsdManager.DiscoveryListener {

            override fun onDiscoveryStarted(serviceType: String) {
                val prefs = getSharedPreferences("raspberry_config", MODE_PRIVATE)
                if (prefs.getString("PI_NAME", null) == null) {
                    showScanningState()
                }
            }

            override fun onServiceFound(service: NsdServiceInfo) {

                if (service.serviceType != "_mbg._tcp.") return

                val nameKey = service.serviceName.lowercase()

                if (resolvingServices.contains(nameKey)) return
                if (discoveredPis.containsKey(nameKey)) return

                resolvingServices.add(nameKey)

                val resolveListener = object : NsdManager.ResolveListener {

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {

                        val host = serviceInfo.host?.hostAddress ?: return
                        val port = serviceInfo.port
                        val url  = "http://$host:$port/"

                        discoveredPis[nameKey] = url
                        resolvingServices.remove(nameKey)

                        runOnUiThread { invalidateOptionsMenu() }
                    }

                    override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                        resolvingServices.remove(nameKey)
                    }
                }

                nsdManager.resolveService(service, resolveListener)
            }

            override fun onServiceLost(service: NsdServiceInfo) {

                val key = service.serviceName.lowercase()
                discoveredPis.remove(key)
                resolvingServices.remove(key)

                if (discoveredPis.isEmpty()) showScanningState()
                invalidateOptionsMenu()
            }

            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                stopDiscovery()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                stopDiscovery()
            }
        }

        nsdManager.discoverServices("_mbg._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    private fun stopDiscovery() {

        if (!isDiscovering) return

        try {
            nsdManager.stopServiceDiscovery(discoveryListener)
        } catch (_: Exception) {}

        isDiscovering = false
    }
}
