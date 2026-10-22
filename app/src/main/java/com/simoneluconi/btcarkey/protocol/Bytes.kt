package com.simoneluconi.btcarkey.protocol

/** Little-endian helpers: every multi-byte field of the protocol is little-endian. */

fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xFF

fun ByteArray.u16(offset: Int): Int = u8(offset) or (u8(offset + 1) shl 8)

fun ByteArray.u32(offset: Int): Long =
    (u16(offset).toLong()) or (u16(offset + 2).toLong() shl 16)

fun ByteArray.putU16(offset: Int, value: Int) {
    this[offset] = value.toByte()
    this[offset + 1] = (value ushr 8).toByte()
}

fun u16Bytes(value: Int): ByteArray = ByteArray(2).also { it.putU16(0, value) }

fun u32Bytes(value: Long): ByteArray = ByteArray(4) { (value ushr (8 * it)).toByte() }

/** Sum of the bytes truncated to 16 bits: the protocol's "checksum". */
fun ByteArray.sum16(from: Int = 0, to: Int = size): Int {
    var sum = 0
    for (i in from until to) sum += this[i].toInt() and 0xFF
    return sum and 0xFFFF
}

fun ByteArray.toHex(separator: String = ""): String =
    joinToString(separator) { "%02x".format(it.toInt() and 0xFF) }

/** Parses hex, tolerating spaces, colons and dashes; null when it isn't valid hex. */
fun String.hexToBytesOrNull(): ByteArray? {
    val clean = filterNot { it == ' ' || it == ':' || it == '-' }
    if (clean.length % 2 != 0) return null
    return runCatching { ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }.getOrNull()
}

/** "A1:B2:C3:D4:E5:F6" from 6 bytes, as the box reports its serial. */
fun ByteArray.toSerial(offset: Int = 0): String =
    (offset until offset + 6).joinToString(":") { "%02X".format(this[it].toInt() and 0xFF) }
