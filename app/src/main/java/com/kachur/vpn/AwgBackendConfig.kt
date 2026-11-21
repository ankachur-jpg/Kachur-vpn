package com.kachur.vpn

import org.amnezia.awg.config.Config

// Feed the official parser every normalized field. It rejects settings newer than this engine.
internal fun AwgConfig.toBackendConfig(): Config = buildString {
    appendLine("[Interface]")
    interfaceSection.appendFields(this)
    for (peer in peers) {
        appendLine("[Peer]")
        peer.appendFields(this)
    }
}.reader().buffered().use(Config::parse)
