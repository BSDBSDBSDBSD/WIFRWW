package com.netfreeguard.app

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var etCheckHost: EditText
    private lateinit var switchProtection: Switch
    private lateinit var settings: SettingsStore

    private val vpnPermissionLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                startProtection()
            } else {
                switchProtection.isChecked = false
                Toast.makeText(this, "אישור VPN נדחה - ההגנה לא תופעל", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settings = SettingsStore(this)
        tvStatus = findViewById(R.id.tvStatus)
        etCheckHost = findViewById(R.id.etCheckHost)
        switchProtection = findViewById(R.id.switchProtection)

        etCheckHost.setText(settings.getCheckHostPort() ?: "")
        switchProtection.isChecked = settings.isProtectionEnabled()

        findViewById<Button>(R.id.btnInstallCerts).setOnClickListener { installCerts() }
        findViewById<Button>(R.id.btnSaveHost).setOnClickListener { saveHost() }

        switchProtection.setOnCheckedChangeListener { _, isChecked ->
            settings.setProtectionEnabled(isChecked)
            if (isChecked) requestVpnAndStart() else stopProtection()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    private fun installCerts() {
        tvStatus.text = "מתקין תעודות... (אשר את בקשת ה-root)"
        Thread {
            val result = CertInstaller.install(applicationContext)
            runOnUiThread {
                tvStatus.text = if (result.success)
                    "התעודות הותקנו בהצלחה ✔"
                else
                    "ההתקנה נכשלה - ראה יומן:\n${result.log}"
            }
        }.start()
    }

    private fun saveHost() {
        val value = etCheckHost.text.toString().trim()
        if (value.isEmpty() || !value.contains(":")) {
            Toast.makeText(this, "יש להזין כתובת בפורמט host:port", Toast.LENGTH_SHORT).show()
            return
        }
        settings.setCheckHostPort(value)
        Toast.makeText(this, "כתובת הבדיקה נשמרה", Toast.LENGTH_SHORT).show()
    }

    private fun requestVpnAndStart() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            startProtection()
        }
    }

    private fun startProtection() {
        val intent = Intent(this, NetworkMonitorService::class.java)
        ContextCompat.startForegroundService(this, intent)
        tvStatus.text = "ההגנה פעילה - עוקב אחרי הרשת"
    }

    private fun stopProtection() {
        stopService(Intent(this, NetworkMonitorService::class.java))
        stopService(Intent(this, LocalBlockVpnService::class.java).apply {
            action = LocalBlockVpnService.ACTION_STOP
        })
        tvStatus.text = "ההגנה כבויה"
    }
}
