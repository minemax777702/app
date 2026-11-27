package com.mok.buylist.cloud

/** Only signed, device-bound access records are delivered through the private repository. */
internal object CloudAccessDelivery {
    fun prefix(deviceId: String): String {
        require(deviceId.matches(Regex("[0-9a-f]{64}")))
        return "refs/heads/cloud-access-v1/$deviceId/"
    }
    fun branch(deviceId: String, space: CloudSpace): String =
        prefix(deviceId) + space.key.replace(':', '/')
}
