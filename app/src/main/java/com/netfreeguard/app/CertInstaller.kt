package com.netfreeguard.app

import android.content.Context
import java.io.DataOutputStream
import java.io.File

/**
 * מתקין את תעודות ה-CA של נטפרי (מתוך assets/cacerts, שמקורן במודול
 * ה-Magisk הרשמי customcacert) לתוך חנות התעודות של המערכת.
 *
 * זו פעולה גלויה, חד-פעמית, המבקשת הרשאת root דרך ה-su הרגיל של המכשיר
 * (Magisk / SuperSU וכו'). אין כאן שום ניסיון לעקוף את מנגנון הבקשה
 * של su, ואין שום דבר שממשיך לפעול בלי שהמשתמש אישר את הבקשה בפועל
 * בממשק ניהול ה-root הרגיל של המכשיר.
 */
object CertInstaller {

    private const val TARGET_DIR = "/system/etc/security/cacerts"

    data class Result(val success: Boolean, val log: String)

    fun install(context: Context): Result {
        val log = StringBuilder()
        try {
            // מעתיקים תחילה את קבצי ה-assets לתיקייה זמנית שנגישה בלי root
            val stagingDir = File(context.filesDir, "cacerts_staging")
            stagingDir.mkdirs()
            val certNames = context.assets.list("cacerts") ?: emptyArray()
            if (certNames.isEmpty()) {
                return Result(false, "לא נמצאו קבצי תעודות ב-assets/cacerts")
            }
            for (name in certNames) {
                context.assets.open("cacerts/$name").use { input ->
                    File(stagingDir, name).outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
            log.appendLine("הועתקו ${certNames.size} תעודות לתיקיית ביניים: ${stagingDir.absolutePath}")

            // בונים סקריפט su יחיד: remount rw -> העתקה -> הרשאות -> remount ro
            val script = buildString {
                appendLine("mount -o rw,remount /system || mount -o rw,remount /")
                appendLine("mkdir -p $TARGET_DIR")
                for (name in certNames) {
                    appendLine("cp '${stagingDir.absolutePath}/$name' '$TARGET_DIR/$name'")
                    appendLine("chmod 644 '$TARGET_DIR/$name'")
                    appendLine("chown root:root '$TARGET_DIR/$name'")
                }
                appendLine("mount -o ro,remount /system || mount -o ro,remount /")
                appendLine("echo NETFREE_GUARD_DONE")
            }

            val output = runAsRoot(script)
            log.appendLine(output)

            val success = output.contains("NETFREE_GUARD_DONE")
            return Result(success, log.toString())
        } catch (e: Exception) {
            log.appendLine("שגיאה: ${e.message}")
            return Result(false, log.toString())
        }
    }

    /**
     * מריץ פקודות דרך su הרגיל של המכשיר. זו הבקשה הסטנדרטית להרשאת
     * root - תופיע לבעל המכשיר בממשק ה-root שלו (למשל Magisk) והוא
     * צריך לאשר אותה במפורש, בדיוק כמו כל אפליקציה אחרת שמבקשת root.
     */
    private fun runAsRoot(script: String): String {
        val process = ProcessBuilder("su").redirectErrorStream(true).start()
        DataOutputStream(process.outputStream).use { stdin ->
            stdin.writeBytes(script)
            stdin.writeBytes("exit\n")
            stdin.flush()
        }
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return output
    }
}
