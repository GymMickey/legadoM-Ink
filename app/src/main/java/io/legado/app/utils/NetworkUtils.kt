package io.legado.app.utils

import android.annotation.SuppressLint
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import cn.hutool.core.lang.Validator
import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig
import okhttp3.internal.publicsuffix.PublicSuffixDatabase
import splitties.systemservices.connectivityManager
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.net.URL
import java.util.BitSet

@Suppress("unused", "MemberVisibilityCanBePrivate")
object NetworkUtils {

    /**
     * 判断是否联网
     */
    @SuppressLint("ObsoleteSdkInt")
    @Suppress("DEPRECATION")
    fun isAvailable(): Boolean {
        if (Build.VERSION.SDK_INT < 23) {
            val mWiFiNetworkInfo = connectivityManager.activeNetworkInfo
            if (mWiFiNetworkInfo != null) {
                // WIFI
                return mWiFiNetworkInfo.type == ConnectivityManager.TYPE_WIFI ||
                        // 移动数据
                        mWiFiNetworkInfo.type == ConnectivityManager.TYPE_MOBILE ||
                        // 以太网
                        mWiFiNetworkInfo.type == ConnectivityManager.TYPE_ETHERNET ||
                        // VPN
                        mWiFiNetworkInfo.type == ConnectivityManager.TYPE_VPN
            }
        } else {
            val network = connectivityManager.activeNetwork
            if (network != null) {
                val nc = connectivityManager.getNetworkCapabilities(network)
                if (nc != null) {
                    // WIFI
                    return nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                            // 移动数据
                            nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                            // 以太网
                            nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                            // VPN
                            nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                }
            }
        }
        return false
    }

    private val notNeedEncodingQuery: BitSet by lazy {
        val bitSet = BitSet(256)
        for (i in 'a'.code..'z'.code) {
            bitSet.set(i)
        }
        for (i in 'A'.code..'Z'.code) {
            bitSet.set(i)
        }
        for (i in '0'.code..'9'.code) {
            bitSet.set(i)
        }
        for (char in "!$&()*+,-./:;=?@[\\]^_`{|}~") {
            bitSet.set(char.code)
        }
        return@lazy bitSet
    }

    private val notNeedEncodingForm: BitSet by lazy {
        val bitSet = BitSet(256)
        for (i in 'a'.code..'z'.code) {
            bitSet.set(i)
        }
        for (i in 'A'.code..'Z'.code) {
            bitSet.set(i)
        }
        for (i in '0'.code..'9'.code) {
            bitSet.set(i)
        }
        for (char in "*-._") {
            bitSet.set(char.code)
        }
        return@lazy bitSet
    }

    /**
     * 支持JAVA的URLEncoder.encode出来的string做判断。 即: 将' '转成'+'
     * 0-9a-zA-Z保留 <br></br>
     * ! * ' ( ) ; : @ & = + $ , / ? # [ ] 保留
     * 其他字符转成%XX的格式，X是16进制的大写字符，范围是[0-9A-F]
     */
    fun encodedQuery(str: String): Boolean {
        var needEncode = false
        var i = 0
        while (i < str.length) {
            val c = str[i]
            if (notNeedEncodingQuery.get(c.code)) {
                i++
                continue
            }
            if (c == '%' && i + 2 < str.length) {
                // 判断是否符合urlEncode规范
                val c1 = str[++i]
                val c2 = str[++i]
                if (isDigit16Char(c1) && isDigit16Char(c2)) {
                    i++
                    continue
                }
            }
            // 其他字符，肯定需要urlEncode
            needEncode = true
            break
        }

        return !needEncode
    }

    fun encodedForm(str: String): Boolean {
        var needEncode = false
        var i = 0
        while (i < str.length) {
            val c = str[i]
            if (notNeedEncodingForm.get(c.code)) {
                i++
                continue
            }
            if (c == '%' && i + 2 < str.length) {
                // 判断是否符合urlEncode规范
                val c1 = str[++i]
                val c2 = str[++i]
                if (isDigit16Char(c1) && isDigit16Char(c2)) {
                    i++
                    continue
                }
            }
            // 其他字符，肯定需要urlEncode
            needEncode = true
            break
        }

        return !needEncode
    }

    /**
     * 判断c是否是16进制的字符
     */
    private fun isDigit16Char(c: Char): Boolean {
        return c in '0'..'9' || c in 'A'..'F' || c in 'a'..'f'
    }

    /**
     * 获取绝对地址
     */
    fun getAbsoluteURL(baseURL: String?, relativePath: String): String {
        if (baseURL.isNullOrEmpty()) return relativePath.trim()
        var absoluteUrl: URL? = null
        try {
            absoluteUrl = URL(baseURL.substringBefore(","))
        } catch (e: Exception) {
            e.printOnDebug()
        }
        return getAbsoluteURL(absoluteUrl, relativePath)
    }

    /**
     * 获取绝对地址
     */
    fun getAbsoluteURL(baseURL: URL?, relativePath: String): String {
        val relativePathTrim = relativePath.trim()
        if (baseURL == null) return relativePathTrim
        if (relativePathTrim.isAbsUrl()) return relativePathTrim
        if (relativePathTrim.isDataUrl()) return relativePathTrim
        if (relativePathTrim.startsWith("javascript")) return ""
        var relativeUrl = relativePathTrim
        try {
            val parseUrl = URL(baseURL, relativePath)
            relativeUrl = parseUrl.toString()
            return relativeUrl
        } catch (e: Exception) {
            AppLog.put("网址拼接出错\n${e.localizedMessage}", e)
        }
        return relativeUrl
    }

    fun getBaseUrl(url: String?): String? {
        url ?: return null
        if (url.startsWith("http://", true)
            || url.startsWith("https://", true)
        ) {
            val index = url.indexOf("/", 9)
            return if (index == -1) {
                url
            } else url.substring(0, index)
        }
        return null
    }

    /**
     * 获取域名，供cookie保存和读取，处理失败返回传入的url
     * http://1.2.3.4 => 1.2.3.4
     * https://www.example.com =>  example.com
     * http://www.biquge.com.cn => biquge.com.cn
     * http://www.content.example.com => example.com
     */
    fun getSubDomain(url: String): String {
        val baseUrl = getBaseUrl(url) ?: return url
        return kotlin.runCatching {
            val mURL = URL(baseUrl)
            val host: String = mURL.host
            //mURL.scheme https/http
            //判断是否为ip
            if (isIPAddress(host)) return host
            //PublicSuffixDatabase处理域名
            PublicSuffixDatabase.get().getEffectiveTldPlusOne(host) ?: host
        }.getOrDefault(baseUrl)
    }

    fun getSubDomainOrNull(url: String): String? {
        val baseUrl = getBaseUrl(url) ?: return null
        return kotlin.runCatching {
            val mURL = URL(baseUrl)
            val host: String = mURL.host
            //mURL.scheme https/http
            //判断是否为ip
            if (isIPAddress(host)) return host
            //PublicSuffixDatabase处理域名
            PublicSuffixDatabase.get().getEffectiveTldPlusOne(host) ?: host
        }.getOrDefault(null)
    }

    fun getDomain(url: String): String {
        val baseUrl = getBaseUrl(url) ?: return url
        return kotlin.runCatching {
            URL(baseUrl).host
        }.getOrDefault(baseUrl)
    }

    /**
     * Returns the one stable IPv4 address used by all user-visible Web service links.
     * Internet capability is intentionally not required: an isolated LAN is valid.
     */
    fun getPreferredLocalIPv4(): String? {
        val candidates = buildList {
            addAll(getNetworkAddressCandidates())
            addAll(getInterfaceAddressCandidates())
        }
        return LanIpv4Selector.select(candidates)
    }

    @SuppressLint("MissingPermission", "ObsoleteSdkInt")
    private fun getNetworkAddressCandidates(): List<LanIpv4Candidate> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return emptyList()

        return try {
            val activeNetwork = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                connectivityManager.activeNetwork
            } else {
                null
            }
            @Suppress("DEPRECATION")
            val activeWifi = Build.VERSION.SDK_INT < Build.VERSION_CODES.M &&
                connectivityManager.activeNetworkInfo?.type == ConnectivityManager.TYPE_WIFI

            connectivityManager.allNetworks.flatMap { network ->
                try {
                    val capabilities = connectivityManager.getNetworkCapabilities(network)
                        ?: return@flatMap emptyList()
                    val linkProperties = connectivityManager.getLinkProperties(network)
                        ?: return@flatMap emptyList()
                    val source = when {
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
                            LanIpv4Source.WIFI
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
                            LanIpv4Source.ETHERNET
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                            LanIpv4Source.CELLULAR
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ->
                            LanIpv4Source.VPN
                        else -> LanIpv4Source.OTHER
                    }
                    val isActiveWifi = source == LanIpv4Source.WIFI &&
                        (network == activeNetwork || activeWifi)
                    linkProperties.linkAddresses.mapNotNull { linkAddress ->
                        val address = linkAddress.address.hostAddress
                            ?: return@mapNotNull null
                        LanIpv4Candidate(
                            address = address,
                            source = source,
                            active = isActiveWifi,
                            interfaceName = linkProperties.interfaceName.orEmpty()
                        )
                    }
                } catch (e: RuntimeException) {
                    e.printOnDebug()
                    emptyList()
                }
            }
        } catch (e: RuntimeException) {
            e.printOnDebug()
            emptyList()
        }
    }

    private fun getInterfaceAddressCandidates(): List<LanIpv4Candidate> {
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().flatMap { networkInterface ->
                val source = sourceForInterface(networkInterface.name)
                networkInterface.inetAddresses?.toList().orEmpty().mapNotNull { address ->
                    val hostAddress = address.hostAddress ?: return@mapNotNull null
                    LanIpv4Candidate(
                        address = hostAddress,
                        source = source,
                        interfaceName = networkInterface.name.orEmpty()
                    )
                }
            }
        } catch (e: SocketException) {
            e.printOnDebug()
            emptyList()
        } catch (e: RuntimeException) {
            e.printOnDebug()
            emptyList()
        }
    }

    private fun sourceForInterface(name: String?): LanIpv4Source {
        val normalized = name.orEmpty().lowercase()
        return when {
            normalized.contains("tun") || normalized.contains("tap") ||
                normalized.contains("ppp") || normalized.contains("vpn") ||
                normalized.contains("wg") -> LanIpv4Source.VPN
            normalized.contains("wlan") || normalized.contains("wifi") -> LanIpv4Source.WIFI
            normalized.contains("eth") -> LanIpv4Source.ETHERNET
            normalized.contains("softap") || normalized.contains("tether") ||
                normalized.matches(Regex("ap\\d+")) -> LanIpv4Source.HOTSPOT
            normalized.contains("rmnet") || normalized.contains("ccmni") ||
                normalized.contains("wwan") || normalized.contains("cell") -> LanIpv4Source.CELLULAR
            else -> LanIpv4Source.PRIVATE
        }
    }

    /**
     * Check if valid IPV4 address.
     *
     * @param input the address string to check for validity.
     * @return True if the input parameter is a valid IPv4 address.
     */
    fun isIPv4Address(input: String?): Boolean {
        return input != null && input.isNotEmpty()
                && input[0] in '1'..'9'
                && input.count { it == '.' } == 3
                && Validator.isIpv4(input)
    }

    /**
     * Check if valid IPV6 address.
     */
    fun isIPv6Address(input: String?): Boolean {
        return input != null && input.contains(":") && Validator.isIpv6(input)
    }

    /**
     * Check if valid IP address.
     */
    fun isIPAddress(input: String?): Boolean {
        return isIPv4Address(input) || isIPv6Address(input)
    }

}
