package com.vtech.interactivetools.websocket

import android.net.Proxy
import android.util.Log
import okhttp3.*
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress

class TimingEventListener : EventListener() {
    private var startTimeNs: Long = 0

    override fun callStart(call: Call) {
        startTimeNs = System.nanoTime()
        Log.d("OkHttpEvent", "callStart")
    }

    override fun dnsStart(call: Call, domainName: String) {
        Log.d("OkHttpEvent", "dnsStart: $domainName")
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        Log.d("OkHttpEvent", "dnsEnd: resolved to $inetAddressList")
    }

    override fun connectStart(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: java.net.Proxy
    ) {
        Log.d("OkHttpEvent", "connectStart: $inetSocketAddress")
    }

    override fun secureConnectStart(call: Call) {
        Log.d("OkHttpEvent", "secureConnectStart")
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        Log.d("OkHttpEvent", "secureConnectEnd")
    }

    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: java.net.Proxy,
        protocol: Protocol?
    ) {
        Log.d("OkHttpEvent", "connectEnd")
    }

    override fun requestHeadersStart(call: Call) {
        Log.d("OkHttpEvent", "requestHeadersStart")
    }

    override fun responseHeadersStart(call: Call) {
        Log.d("OkHttpEvent", "responseHeadersStart")
    }

    override fun responseBodyStart(call: Call) {
        Log.d("OkHttpEvent", "responseBodyStart")
    }

    override fun callEnd(call: Call) {
        val tookMs = (System.nanoTime() - startTimeNs) / 1_000_000
        Log.d("OkHttpEvent", "callEnd - total time: $tookMs ms")
    }

    override fun callFailed(call: Call, ioe: IOException) {
        Log.d("OkHttpEvent", "callFailed: ${ioe.message}")
    }
}