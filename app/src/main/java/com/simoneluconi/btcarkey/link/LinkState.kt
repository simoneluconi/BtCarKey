package com.simoneluconi.btcarkey.link

import com.simoneluconi.btcarkey.auth.AuthStep
import com.simoneluconi.btcarkey.protocol.CarCommand
import com.simoneluconi.btcarkey.protocol.DeviceInfo
import com.simoneluconi.btcarkey.protocol.KeyProtocol

/** Where the connection to the selected device stands; drives the step indicator on the home screen. */
sealed interface LinkState {
    /** No device added yet. */
    data object NoDevice : LinkState

    /** The app is in the background: nothing runs, nothing drains the battery. */
    data object Idle : LinkState

    data object BluetoothOff : LinkState

    data object NeedsPermission : LinkState

    /** Short scan to learn (or refresh) the device's address. */
    data class Locating(val serial: String) : LinkState

    /**
     * Connecting. [waiting] is the low-power wait: the Bluetooth controller connects by itself as
     * soon as the device is in range (out of range, or after a link loss).
     */
    data class Connecting(val serial: String, val waiting: Boolean) : LinkState

    data class Authenticating(val serial: String, val step: AuthStep) : LinkState

    /** Authenticated: commands can be sent. [newPairing] when this connection set a new key. */
    data class Ready(val serial: String, val info: DeviceInfo, val newPairing: Boolean, val protocol: KeyProtocol) : LinkState

    /** Stopped on something the driver has to look at; no automatic retry (no battery drain). */
    data class Problem(val serial: String?, val kind: ProblemKind, val detail: String) : LinkState
}

enum class ProblemKind {
    /** The device at the saved address doesn't speak its protocol (wrong service), or the protocol is unknown. */
    NOT_SUPPORTED,

    /** The device holds a key and none is stored here. */
    KEY_MISSING,

    /** The device refused the stored key(s). */
    KEY_REJECTED,

    /** The device refused a new key. */
    PAIRING_FAILED,

    /** Unexpected answers or none at all. */
    PROTOCOL,

    /** The device wasn't found while scanning for its address. */
    NOT_FOUND,
}

/** Outcome of a car command, for the button's feedback. */
sealed interface CommandResult {
    val command: CarCommand

    data class Done(override val command: CarCommand) : CommandResult

    /** Sent, but the device didn't answer: it may well have worked. */
    data class Unconfirmed(override val command: CarCommand) : CommandResult

    data class Failed(override val command: CarCommand, val reason: String) : CommandResult
}
