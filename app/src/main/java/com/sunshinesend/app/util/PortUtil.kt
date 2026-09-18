package com.sunshinesend.app.util

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 局域网工具类。
 *
 * 使用示例：
 * ```kotlin
 * val ip = PortUtil.lan()                     // "192.168.1.23"，取不到时为 null
 * val url = "http://${ip ?: "127.0.0.1"}:${SimpleServer.PORT}"
 * ```
 */
object PortUtil {

    /**
     * 获取本机在局域网中的 IPv4 地址。
     *
     * 遍历所有已启用（up）的非回环网卡，返回第一个找到的
     * 非回环 [Inet4Address]。典型的 TV 场景下即 Wi-Fi / 以太网地址。
     *
     * @return 形如 "192.168.1.23" 的 IP 字符串；获取失败（无网络、
     *         权限异常等）时返回 null，调用方应回退到 127.0.0.1
     */
    fun lan(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        return address.hostAddress
                    }
                }
            }
            null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
