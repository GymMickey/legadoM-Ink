package io.legado.app.utils

/** A network address candidate used by the single local Web service address selector. */
internal data class LanIpv4Candidate(
    val address: String,
    val source: LanIpv4Source,
    val active: Boolean = false,
    val interfaceName: String = ""
)

internal enum class LanIpv4Source {
    WIFI,
    ETHERNET,
    HOTSPOT,
    PRIVATE,
    OTHER,
    CELLULAR,
    VPN
}

internal object LanIpv4Selector {

    fun select(candidates: Iterable<LanIpv4Candidate>): String? {
        return candidates.asSequence()
            .filter { isUsableIpv4(it.address) }
            .sortedWith(
                compareBy<LanIpv4Candidate>(
                    { sourcePriority(it.source, it.active) },
                    { if (isPrivateIpv4(it.address)) 0 else 1 },
                    { it.address },
                    { it.interfaceName }
                )
            )
            .map { it.address }
            .firstOrNull()
    }

    internal fun isUsableIpv4(address: String): Boolean {
        val parts = address.split('.')
        if (parts.size != 4 || parts.any { it.isEmpty() || it.toIntOrNull() !in 0..255 }) {
            return false
        }
        val first = parts[0].toInt()
        val second = parts[1].toInt()
        return first != 0
            && first != 127
            && !(first == 169 && second == 254)
            && first !in 224..239
            && address != "255.255.255.255"
    }

    internal fun isPrivateIpv4(address: String): Boolean {
        if (!isUsableIpv4(address)) return false
        val parts = address.split('.').map(String::toInt)
        val first = parts[0]
        val second = parts[1]
        return first == 10
            || (first == 172 && second in 16..31)
            || (first == 192 && second == 168)
    }

    private fun sourcePriority(source: LanIpv4Source, active: Boolean): Int {
        return when (source) {
            LanIpv4Source.WIFI -> if (active) 0 else 10
            LanIpv4Source.ETHERNET -> 20
            LanIpv4Source.HOTSPOT -> 30
            LanIpv4Source.PRIVATE -> 40
            LanIpv4Source.OTHER -> 50
            LanIpv4Source.CELLULAR -> 60
            LanIpv4Source.VPN -> 70
        }
    }
}
