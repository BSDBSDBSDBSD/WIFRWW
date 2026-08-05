package com.netfreeguard.app

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor

/**
 * VPN מקומי "חור שחור": כשהוא פעיל, כל התעבורה של המכשיר מנותבת אליו
 * ולא יוצאת החוצה בפועל (כי אנחנו לא מעבירים אף חבילה הלאה). זו הדרך
 * הרשמית והתקנית של אנדרואיד לחסום אינטרנט ברמת האפליקציה, בלי root
 * ובלי לגעת בהגדרות מערכת - בדיוק כמו אפליקציות בקרת הורים וחומות אש
 * מקומיות (NetGuard וכו').
 *
 * השירות גלוי לגמרי: כשהוא פעיל, אנדרואיד מציג אייקון מפתח/VPN קבוע
 * בשורת הסטטוס - זו התנהגות מובנית של המערכת שאי אפשר (ואסור) לעקוף.
 */
class LocalBlockVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null

    companion object {
        const val ACTION_START = "com.netfreeguard.app.action.START_BLOCK"
        const val ACTION_STOP = "com.netfreeguard.app.action.STOP_BLOCK"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startBlocking()
            ACTION_STOP -> stopBlocking()
        }
        return START_STICKY
    }

    private fun startBlocking() {
        if (vpnInterface != null) return // כבר פעיל

        val builder = Builder()
            .setSession("Netfree Guard - חסימת רשת לא מסוננת")
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0) // כל התעבורה נכנסת ל"חור" - לא יוצאת בפועל
            .setBlocking(false)

        vpnInterface = builder.establish()
        // בכוונה לא קוראים/כותבים לתוך ה-file descriptor: כל חבילה שנכנסת
        // לממשק הזה פשוט לא ממשיכה לשום מקום, ובכך התעבורה בפועל חסומה.
    }

    private fun stopBlocking() {
        vpnInterface?.close()
        vpnInterface = null
        stopSelf()
    }

    override fun onDestroy() {
        vpnInterface?.close()
        vpnInterface = null
        super.onDestroy()
    }
}
