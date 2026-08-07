package com.netfreeguard.app

import android.content.Context
import java.io.DataOutputStream
import java.io.File

/**
 * Installs a small always-on watchdog directly onto /system (not /data),
 * so it survives a normal factory reset (Settings -> Reset), independent
 * of Magisk or any installed app.
 *
 * Detection method: for any HTTPS connection genuinely passing through
 * NetFree's filtering, NetFree re-signs the certificate with its own CA
 * ("O = NetFree" in the issuer). We open a TLS connection to a stable
 * public host and check the issuer field. If it's NOT signed by NetFree,
 * we block all regular app traffic (UID range 10000-19999) via iptables,
 * while always letting our own root-owned check traffic through — that's
 * what lets the watchdog keep re-checking (and auto-unblock) even while
 * the block is active.
 */
object NetworkGuardManager {

    private const val SCRIPT_PATH = "/system/etc/netfree_guard.sh"
    private const val RC_PATH = "/system/etc/init/netfreeguard.rc"
    private const val CHAIN = "netfree_guard"

    private val scriptContent = """
        #!/system/bin/sh
        CHAIN="$CHAIN"
        TARGET_HOST="google.com"
        TARGET_PORT="443"

        apply_block() {
            iptables -N ${'$'}CHAIN 2>/dev/null
            iptables -F ${'$'}CHAIN
            iptables -A ${'$'}CHAIN -m owner --uid-owner 0 -j RETURN
            iptables -A ${'$'}CHAIN -o lo -j RETURN
            iptables -A ${'$'}CHAIN -p udp --dport 53 -j RETURN
            iptables -A ${'$'}CHAIN -m owner --uid-owner 10000-19999 -j REJECT
            iptables -D OUTPUT -j ${'$'}CHAIN 2>/dev/null
            iptables -I OUTPUT -j ${'$'}CHAIN
        }

        remove_block() {
            iptables -D OUTPUT -j ${'$'}CHAIN 2>/dev/null
            iptables -F ${'$'}CHAIN 2>/dev/null
            iptables -X ${'$'}CHAIN 2>/dev/null
        }

        while true; do
            ISSUER=${'$'}(echo | timeout 5 openssl s_client -connect ${'$'}TARGET_HOST:${'$'}TARGET_PORT -servername ${'$'}TARGET_HOST 2>/dev/null | openssl x509 -noout -issuer 2>/dev/null)
            if echo "${'$'}ISSUER" | grep -q "NetFree"; then
                remove_block
            else
                apply_block
            fi
            sleep 30
        done
    """.trimIndent()

    private val rcContent = """
        service netfreeguard /system/bin/sh /system/etc/netfree_guard.sh
            class late_start
            user root
            group root
            disabled

        on property:sys.boot_completed=1
            start netfreeguard
    """.trimIndent()

    data class Result(val success: Boolean, val log: String)

    /** Writes the watchdog script + init service to /system and starts it immediately. */
    fun install(context: Context): Result {
        val stagingScript = File(context.filesDir, "netfree_guard.sh")
        stagingScript.writeText(scriptContent)
        val stagingRc = File(context.filesDir, "netfreeguard.rc")
        stagingRc.writeText(rcContent)

        val script = buildString {
            appendLine("mount -o rw,remount /system || mount -o rw,remount /")
            appendLine("cp '${stagingScript.absolutePath}' $SCRIPT_PATH")
            appendLine("chmod 755 $SCRIPT_PATH")
            appendLine("chown root:root $SCRIPT_PATH")
            appendLine("mkdir -p /system/etc/init")
            appendLine("cp '${stagingRc.absolutePath}' $RC_PATH")
            appendLine("chmod 644 $RC_PATH")
            appendLine("chown root:root $RC_PATH")
            appendLine("mount -o ro,remount /system || mount -o ro,remount /")
            // start it immediately too, not just on next boot
            appendLine("nohup sh $SCRIPT_PATH >/dev/null 2>&1 &")
            appendLine("echo NETFREE_GUARD_INSTALLED")
        }
        val output = runAsRoot(script)
        return Result(output.contains("NETFREE_GUARD_INSTALLED"), output)
    }

    /** Removes the watchdog script, init service, and any active block rules. */
    fun uninstall(): Result {
        val script = buildString {
            appendLine("pkill -f netfree_guard.sh 2>/dev/null")
            appendLine("mount -o rw,remount /system || mount -o rw,remount /")
            appendLine("rm -f $SCRIPT_PATH")
            appendLine("rm -f $RC_PATH")
            appendLine("mount -o ro,remount /system || mount -o ro,remount /")
            appendLine("iptables -D OUTPUT -j $CHAIN 2>/dev/null")
            appendLine("iptables -F $CHAIN 2>/dev/null")
            appendLine("iptables -X $CHAIN 2>/dev/null")
            appendLine("echo NETFREE_GUARD_REMOVED")
        }
        val output = runAsRoot(script)
        return Result(output.contains("NETFREE_GUARD_REMOVED"), output)
    }

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
