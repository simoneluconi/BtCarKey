package com.simoneluconi.btcarkey.link

/**
 * What the BLE library's own trace may say in the app's log. Its lines carry the raw bytes of
 * every write and notification ("value=0x…", "value: (0x) …"), and a pairing write holds the
 * device key in clear: the bytes are cut out. The frames are still logged, by the protocol
 * session, with key material redacted. Signal readings (every second during smart unlock) are
 * dropped: the decisions are logged instead.
 */
object GattLogFilter {
    private val hexValue = Regex("""value(=| ?: ?\(0x\) ?)(0x)?[0-9A-Fa-f][0-9A-Fa-f\- ]*""")

    /** The line to log, or null to drop it. */
    fun filter(message: String): String? {
        if (message.startsWith("Remote RSSI") || message.startsWith("gatt.readRemoteRssi")) return null
        return hexValue.replace(message) { match ->
            val digits = match.value.substringAfter("0x", match.value).count { it.isLetterOrDigit() }
            "value=<${digits / 2} bytes>"
        }
    }
}
