package com.netfreeguard.app

import android.content.Context
import java.net.InetSocketAddress
import java.net.Socket

/**
 * בודק האם הרשת הנוכחית מנותבת דרך תשתית הסינון של נטפרי.
 *
 * חשוב: אני לא יודע בוודאות מהי כתובת/פורט הבדיקה הרשמיים של נטפרי -
 * זה מידע שרק נטפרי עצמה (או התיעוד/התמיכה שלהם) יכולים לספק בוודאות.
 * לכן הכתובת מוגדרת על ידך במסך ההגדרות (SettingsStore), ולא מוטמעת
 * בקוד בתור "אמת" שלא ניתן לאמת אותה.
 *
 * ברירת המחדל: הצלחה בהתחברות ל-host:port המוגדר = נחשב "מסונן" (מותר).
 * כישלון/timeout = נחשב "לא מסונן" (חסום). אם ההיגיון הפוך אצל נטפרי,
 * אפשר להפוך את זה בקלות ב-SettingsStore.invertLogic.
 */
object FilterCheck {

    private const val TIMEOUT_MS = 4000

    fun isNetworkFiltered(context: Context): Boolean {
        val settings = SettingsStore(context)
        val target = settings.getCheckHostPort() ?: return false // בלי הגדרה, נחשב לא מסונן -> חוסם ליתר בטחון

        val parts = target.split(":")
        if (parts.size != 2) return false
        val host = parts[0]
        val port = parts[1].toIntOrNull() ?: return false

        val reachable = try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), TIMEOUT_MS)
                true
            }
        } catch (e: Exception) {
            false
        }

        return if (settings.isInvertLogic()) !reachable else reachable
    }
}
