package com.netfreeguard.app

import android.content.Context
import java.io.DataOutputStream
import java.io.File

/**
 * Handles the permanent, filesystem-level swap of the system's trusted CA
 * certificates. Unlike LocalBlockVpnService (which needs a running app to
 * keep enforcing anything), this operates directly on
 * /system/etc/security/cacerts, so the effect persists with no app running
 * at all — which is what makes it safe for the Lock app to delete itself
 * afterwards.
 *
 * Before ever touching the system certs, the CURRENT ones are backed up to
 * a root-only location outside any app's sandbox (/data/local so it isn't
 * tied to either app's package and survives the Lock app being uninstalled).
 * The Key app later restores from that same backup.
 */
object CaCertsBackupManager {

    private const val SYSTEM_CACERTS = "/system/etc/security/cacerts"
    private const val BACKUP_DIR = "/data/local/netfree_lock_backup/cacerts"

    data class Result(val success: Boolean, val log: String)

    /** LOCK flow: back up current certs, then replace them with only the bundled Netfree ones. */
    fun backupAndReplace(context: Context): Result {
        val log = StringBuilder()
        try {
            val stagingDir = File(context.filesDir, "cacerts_staging")
            stagingDir.deleteRecursively()
            stagingDir.mkdirs()
            val certNames = context.assets.list("cacerts") ?: emptyArray()
            if (certNames.isEmpty()) {
                return Result(false, "לא נמצאו קבצי תעודות נטפרי ב-assets/cacerts")
            }
            for (name in certNames) {
                context.assets.open("cacerts/$name").use { input ->
                    File(stagingDir, name).outputStream().use { output -> input.copyTo(output) }
                }
            }
            log.appendLine("הועתקו ${certNames.size} תעודות נטפרי לתיקיית ביניים")

            val script = buildString {
                appendLine("mount -o rw,remount /system || mount -o rw,remount /")
                appendLine("mkdir -p $BACKUP_DIR")
                // Only back up if we haven't already (avoid overwriting a real
                // backup with an already-replaced state if this is run twice).
                appendLine("if [ -z \"\$(ls -A $BACKUP_DIR 2>/dev/null)\" ]; then cp -r $SYSTEM_CACERTS/. $BACKUP_DIR/; fi")
                appendLine("rm -f $SYSTEM_CACERTS/*")
                for (name in certNames) {
                    appendLine("cp '${stagingDir.absolutePath}/$name' '$SYSTEM_CACERTS/$name'")
                    appendLine("chmod 644 '$SYSTEM_CACERTS/$name'")
                    appendLine("chown root:root '$SYSTEM_CACERTS/$name'")
                }
                appendLine("mount -o ro,remount /system || mount -o ro,remount /")
                appendLine("echo NETFREE_LOCK_DONE")
            }

            val output = runAsRoot(script)
            log.appendLine(output)
            return Result(output.contains("NETFREE_LOCK_DONE"), log.toString())
        } catch (e: Exception) {
            log.appendLine("שגיאה: ${e.message}")
            return Result(false, log.toString())
        }
    }

    /** Deletes the Lock app itself via root, after the swap succeeded. */
    fun uninstallSelf(context: Context): Boolean {
        val output = runAsRoot("pm uninstall ${context.packageName}\necho NETFREE_UNINSTALL_DONE")
        return output.contains("NETFREE_UNINSTALL_DONE") || output.contains("Success")
    }

    fun hasBackup(): Boolean {
        val output = runAsRoot(
            "if [ -d $BACKUP_DIR ] && [ -n \"\$(ls -A $BACKUP_DIR 2>/dev/null)\" ]; then echo YES; else echo NO; fi"
        )
        return output.contains("YES")
    }

    /** KEY flow: restore the originally-backed-up certs, undoing the lock. */
    fun restoreBackup(): Result {
        val script = buildString {
            appendLine("if [ ! -d $BACKUP_DIR ] || [ -z \"\$(ls -A $BACKUP_DIR 2>/dev/null)\" ]; then echo NETFREE_KEY_NO_BACKUP; exit 1; fi")
            appendLine("mount -o rw,remount /system || mount -o rw,remount /")
            appendLine("rm -f $SYSTEM_CACERTS/*")
            appendLine("cp -r $BACKUP_DIR/. $SYSTEM_CACERTS/")
            appendLine("chmod 644 $SYSTEM_CACERTS/*")
            appendLine("chown root:root $SYSTEM_CACERTS/*")
            appendLine("mount -o ro,remount /system || mount -o ro,remount /")
            appendLine("echo NETFREE_KEY_DONE")
        }
        val output = runAsRoot(script)
        return Result(output.contains("NETFREE_KEY_DONE"), output)
    }

    fun isRootAvailable(): Boolean = runAsRoot("id").contains("uid=0")

    private fun runAsRoot(script: String): String {
        return try {
            val process = ProcessBuilder("su").redirectErrorStream(true).start()
            DataOutputStream(process.outputStream).use { stdin ->
                stdin.writeBytes(script)
                stdin.writeBytes("\nexit\n")
                stdin.flush()
            }
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }
}
