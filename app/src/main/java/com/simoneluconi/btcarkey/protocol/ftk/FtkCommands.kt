package com.simoneluconi.btcarkey.protocol.ftk

import com.simoneluconi.btcarkey.protocol.CarCommand
import com.simoneluconi.btcarkey.protocol.RawConfigKind
import com.simoneluconi.btcarkey.protocol.RemoteButton
import java.util.UUID

/** GATT layout and advertising of FTK boxes (see PROTOCOL.md). */
object FtkGatt {
    val SERVICE: UUID = UUID.fromString("8849a9d0-d75f-0c8c-2348-de2d580f2f24")
    /** One characteristic for both directions: Write Without Response out, notifications in. */
    val DATA: UUID = UUID.fromString("8849a9d1-d75f-0c8c-2348-de2d580f2f24")
    /** Manufacturer Specific Data company id; its 6-byte payload is the box's serial. */
    const val COMPANY_ID = 0xF715
    const val MTU = 247
}

/** Top-level command numbers (frame header, offset 6). */
object Cmd {
    const val PING = 1
    const val INFO_SYNC = 2
    const val INFO_REPORT = 3
    const val SYSTEM_CTRL = 4
    const val FLASH_OPT = 5
    const val AUTH = 6
    const val SYSTEM_CFG = 7

    fun name(cmd: Int): String = when (cmd) {
        0 -> "NULL"
        PING -> "PING"
        INFO_SYNC -> "INFO_SYNC"
        INFO_REPORT -> "INFO_REPORT"
        SYSTEM_CTRL -> "SYSTEM_CTRL"
        FLASH_OPT -> "FLASH_OPT"
        AUTH -> "AUTH"
        SYSTEM_CFG -> "SYSTEM_CFG"
        else -> "CMD_$cmd"
    }
}

/** Sub-commands of [Cmd.AUTH]. */
object AuthKey {
    const val AUTH = 0
    const val SET_DEVICE_KEY = 1
    const val VERIFY_DEVICE_KEY = 2
    const val UNBIND = 3
}

/** Sub-commands of [Cmd.SYSTEM_CTRL]. */
object CtrlKey {
    /** Answer: 1 byte, the phone's signal at the box as a positive number (-dBm). */
    const val RSSI = 107
    /** Payload: uint16 seconds; the box then learns the NFC card held to it. */
    const val NFC_REGISTER = 113

    /** One-tap commands, no payload. */
    fun of(command: CarCommand): Int = when (command) {
        CarCommand.UNLOCK -> 100
        CarCommand.LOCK -> 101
        CarCommand.TRUNK -> 115
        CarCommand.REMOTE_START -> 106
        CarCommand.DROP_WINDOWS -> 114
    }

    /**
     * The remote's buttons held down, with 1 byte: 0 = pressed, 1 = released. The box keeps the
     * button pressed in between (long press on the original remote).
     */
    fun of(button: RemoteButton): Int = when (button) {
        RemoteButton.UNLOCK -> 104
        RemoteButton.LOCK -> 105
        RemoteButton.TRUNK -> 102
        RemoteButton.FUNCTION -> 103
    }
}

/** Sub-commands of [Cmd.SYSTEM_CFG]. */
object CfgKey {
    const val AUTO_LOCK = 0
    const val AUTO_LOCK_READ = 32

    /** Opaque vendor blobs: what locking/unlocking/remote start also do, and the car type. */
    fun of(kind: RawConfigKind): Int = when (kind) {
        RawConfigKind.LOCK -> 1
        RawConfigKind.REMOTE_START -> 3
        RawConfigKind.UNLOCK -> 4
        RawConfigKind.CAR_TYPE -> 7
    }
}

/** errorCode values in the box's answers. */
object ResultCode {
    const val OK = 0L

    fun describe(code: Long): String = when (code) {
        0L -> "OK"
        1L -> "ERROR"
        2L -> "UNKNOWN"
        3L -> "DATA_LENGTH_ERROR"
        4L -> "DEVICE_KEY_EXIST"
        5L -> "USER_PAIRED_TOO_MANY"
        in 100L..104L -> "NFC_ERROR_$code"
        105L, 106L -> "FILESYSTEM_ERROR_$code"
        else -> "CODE_$code"
    }
}
