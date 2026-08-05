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

        setupPermanentSection()
    }

    // --- Permanent system-level lock / key section ---

    private fun setupPermanentSection() {
        val tvTitle = findViewById<TextView>(R.id.tvPermanentTitle)
        val tvDescription = findViewById<TextView>(R.id.tvPermanentDescription)
        val btnAction = findViewById<Button>(R.id.btnPermanentAction)
        val tvLog = findViewById<TextView>(R.id.tvPermanentLog)

        if (BuildConfig.IS_LOCK) {
            tvTitle.text = "נעילה קבועה (Netfree Lock)"
            tvDescription.text = "מגבה את תעודות ה-CA הנוכחיות של המכשיר, ואז מחליף אותן " +
                "בתעודות נטפרי בלבד - קבוע, ברמת המערכת, ולא תלוי באפליקציה רצה. " +
                "לאחר מכן האפליקציה הזו תמחק את עצמה. אזהרה: אתרים ואפליקציות שלא " +
                "עוברים דרך סינון נטפרי (כולל אפליקציות בנקים עם certificate pinning) " +
                "עלולים להפסיק לעבוד לגמרי."
            btnAction.text = "בצע נעילה קבועה ומחק את האפליקציה"
            btnAction.setOnClickListener { confirmLock(tvLog) }
        } else {
            tvTitle.text = "שחרור נעילה (Netfree Key)"
            tvDescription.text = "משחזר את תעודות ה-CA המקוריות שגובו על ידי Netfree Lock, " +
                "ומבטל את הנעילה הקבועה."
            btnAction.text = "שחרר נעילה (שחזר תעודות מקוריות)"
            btnAction.setOnClickListener { confirmUnlock(tvLog) }
        }
    }

    private fun confirmLock(tvLog: TextView) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("נעילה קבועה")
            .setMessage(
                "פעולה זו תגבה את תעודות ה-CA הנוכחיות, תחליף אותן בתעודות נטפרי בלבד, " +
                    "ותמחק את האפליקציה הזו. בלי האפליקציה השנייה (Netfree Key) לא תוכל " +
                    "לשחזר בעצמך. להמשיך?"
            )
            .setPositiveButton("נעל") { _, _ -> performLock(tvLog) }
            .setNegativeButton("ביטול", null)
            .show()
    }

    private fun performLock(tvLog: TextView) {
        tvLog.text = "בודק הרשאת root..."
        Thread {
            if (!CaCertsBackupManager.isRootAvailable()) {
                runOnUiThread { tvLog.text = "לא זוהתה הרשאת root" }
                return@Thread
            }
            runOnUiThread { tvLog.text = "מגבה ומחליף תעודות... (אשר את בקשת ה-root)" }
            val result = CaCertsBackupManager.backupAndReplace(applicationContext)
            if (!result.success) {
                runOnUiThread { tvLog.text = "הנעילה נכשלה:\n${result.log}" }
                return@Thread
            }
            runOnUiThread { tvLog.text = "הנעילה בוצעה. מוחק את האפליקציה..." }
            CaCertsBackupManager.uninstallSelf(applicationContext)
            runOnUiThread { tvLog.text = "בוצע. האפליקציה תוסר כעת." }
        }.start()
    }

    private fun confirmUnlock(tvLog: TextView) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("שחרור נעילה")
            .setMessage("פעולה זו תשחזר את תעודות ה-CA המקוריות ותבטל את הנעילה הקבועה. להמשיך?")
            .setPositiveButton("שחרר") { _, _ -> performUnlock(tvLog) }
            .setNegativeButton("ביטול", null)
            .show()
    }

    private fun performUnlock(tvLog: TextView) {
        tvLog.text = "בודק הרשאת root..."
        Thread {
            if (!CaCertsBackupManager.isRootAvailable()) {
                runOnUiThread { tvLog.text = "לא זוהתה הרשאת root" }
                return@Thread
            }
            if (!CaCertsBackupManager.hasBackup()) {
                runOnUiThread { tvLog.text = "לא נמצא גיבוי במכשיר הזה - אין מה לשחזר" }
                return@Thread
            }
            runOnUiThread { tvLog.text = "משחזר תעודות מקוריות..." }
            val result = CaCertsBackupManager.restoreBackup()
            runOnUiThread {
                tvLog.text = if (result.success) "הנעילה שוחררה בהצלחה ✔" else "השחזור נכשל:\n${result.log}"
            }
        }.start()
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
