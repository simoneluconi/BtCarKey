package com.simoneluconi.btcarkey.protocol

import com.simoneluconi.btcarkey.protocol.ftk.FtkProtocol

/** The protocols the app knows, in the order they are tried on an advertisement. */
object Protocols {
    val all: List<KeyProtocol> = listOf(FtkProtocol)

    /** Devices saved before protocols had ids are FTK boxes. */
    val default: KeyProtocol = FtkProtocol

    fun byId(id: String?): KeyProtocol? = all.firstOrNull { it.id == id }

    /** The protocol an advertisement belongs to, and the device's serial. */
    fun identify(advert: Advertisement): Pair<KeyProtocol, String>? {
        for (protocol in all) {
            protocol.identify(advert)?.let { return protocol to it }
        }
        return null
    }

    /** Every protocol's scan filters, for one scan that finds them all. */
    val scanFilters: List<AdFilter> get() = all.flatMap { it.scanFilters }.distinct()
}
