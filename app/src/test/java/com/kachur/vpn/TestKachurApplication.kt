package com.kachur.vpn

import javax.crypto.spec.SecretKeySpec

class TestKachurApplication : KachurApplication() {
    override fun profileKey() = SecretKeySpec(ByteArray(32) { 42 }, "AES")
}
