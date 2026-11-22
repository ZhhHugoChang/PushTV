package com.example.pushtv.network

import android.os.SystemClock
import com.example.pushtv.data.GitHubProxyNode
import com.example.pushtv.data.GitHubProxySettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap

internal object GitHubProxyClient {
    private const val PROBE_APK =
        "https://github.com/ZhhHugoChang/PushTV/releases/download/v1.6.0/PushTV_v1.6-legacy_release_armeabi-v7a.apk"
    private const val PROBE_TTL_MILLIS = 30 * 60 * 1000L
    private const val MAX_PROBE_RECORDS = 50

    enum class ConnectionStatus(val description: String) {
        DIRECT("直连"),
        UNTESTED("代理未测速"),
        AVAILABLE("代理可用"),
        TIMEOUT("代理超时"),
        UNAVAILABLE("代理不可用")
    }

    data class Probe(val status: String, val latencyMs: Long? = null) {
        val tone: String get() = when {
            status == "timeout" -> "timeout"
            status != "available" || latencyMs == null -> "unavailable"
            latencyMs < 1_000 -> "fast"
            latencyMs < 2_000 -> "moderate"
            else -> "slow"
        }

        val displayLabel: String get() = when (status) {
            "available" -> "$latencyMs ms"
            "timeout" -> "超时"
            else -> "不可用"
        }
    }
    private data class RecordedProbe(val result: Probe, val at: Long)
    private val probes = ConcurrentHashMap<String, RecordedProbe>()
    private val probeRevision = MutableStateFlow(0L)
    private val probeSlots = Semaphore(3)

    val client by lazy {
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                connectTimeoutMillis = 8_000
            }
            engine {
                config {
                    proxy(Proxy.NO_PROXY)
                    followRedirects(false)
                    followSslRedirects(false)
                    addInterceptor { chain ->
                        val url = chain.request().url
                        if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() ||
                            !url.host.contains('.') || url.host.none { it.isLetter() }
                        ) throw IOException("代理地址必须使用 HTTPS 域名")
                        if (url.host == "github.com" || url.host.endsWith(".github.com") ||
                            url.host == "githubusercontent.com" || url.host.endsWith(".githubusercontent.com")
                        ) throw IOException("代理将请求转回了 GitHub 直连")
                        chain.proceed(chain.request())
                    }
                }
            }
        }
    }

    fun recentProbe(node: GitHubProxyNode): Probe? = probes[node.prefix]
        ?.takeIf { SystemClock.elapsedRealtime() - it.at < PROBE_TTL_MILLIS }?.result

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeConnectionStatus(settings: GitHubProxySettings): Flow<ConnectionStatus> {
        val nodes = when (settings.selection) {
            GitHubProxySettings.AUTO -> settings.nodes
            else -> listOfNotNull(settings.selectedNode)
        }
        if (nodes.isEmpty()) return flowOf(ConnectionStatus.DIRECT)

        return probeRevision.transformLatest {
            while (true) {
                val now = SystemClock.elapsedRealtime()
                val records = nodes.map { node ->
                    probes[node.prefix]?.takeIf { now - it.at < PROBE_TTL_MILLIS }
                }
                emit(when {
                    records.any { it?.result?.status == "available" } -> ConnectionStatus.AVAILABLE
                    records.any { it == null } -> ConnectionStatus.UNTESTED
                    records.any { it?.result?.status == "timeout" } -> ConnectionStatus.TIMEOUT
                    else -> ConnectionStatus.UNAVAILABLE
                })

                val nextExpiry = records.filterNotNull().minOfOrNull { it.at + PROBE_TTL_MILLIS }
                    ?: awaitCancellation()
                delay((nextExpiry - SystemClock.elapsedRealtime()).coerceAtLeast(1))
            }
        }.distinctUntilChanged()
    }

    suspend fun probeAll(nodes: List<GitHubProxyNode>) = coroutineScope {
        nodes.map { node -> async { node.id to probe(node) } }.awaitAll().toMap()
    }

    suspend fun selectRoutes(
        settings: GitHubProxySettings,
        check: suspend (GitHubProxyNode?) -> Boolean
    ): List<GitHubProxyNode?> = coroutineScope {
        if (settings.selection == GitHubProxySettings.DIRECT) return@coroutineScope listOf(null)

        val selected = settings.selectedNode
        val candidates = settings.nodes.distinctBy { it.prefix }
            .filter { it.prefix != selected?.prefix } + listOf(null)
        if (selected != null && check(selected)) return@coroutineScope listOf(selected) + candidates

        val results = Channel<Pair<GitHubProxyNode?, Boolean>>(candidates.size)
        val probes = candidates.map { node -> launch { results.send(node to check(node)) } }
        val failed = mutableSetOf<GitHubProxyNode?>()
        try {
            repeat(candidates.size) {
                val (node, available) = results.receive()
                if (available) {
                    return@coroutineScope listOf(node) + candidates.filter { it != node && it !in failed }
                }
                failed.add(node)
            }
            throw IOException("直连和代理都未返回有效的 APK 响应")
        } finally {
            probes.forEach { it.cancel() }
            results.cancel()
        }
    }

    suspend fun probeDownload(httpClient: HttpClient, url: String): Boolean =
        probeApk(httpClient, url).status == "available"

    suspend fun probe(node: GitHubProxyNode): Probe = probeSlots.withPermit {
        val result = probeApk(client, node.downloadUrl(PROBE_APK))
        synchronized(probes) {
            if (probes.size >= MAX_PROBE_RECORDS && !probes.containsKey(node.prefix)) {
                probes.entries.minByOrNull { it.value.at }?.let { probes.remove(it.key) }
            }
            probes[node.prefix] = RecordedProbe(result, SystemClock.elapsedRealtime())
            probeRevision.value++
        }
        result
    }

    private suspend fun probeApk(httpClient: HttpClient, url: String): Probe {
        val started = SystemClock.elapsedRealtime()
        return try {
            httpClient.prepareGet(url) {
                header("Range", "bytes=0-3")
                timeout {
                    requestTimeoutMillis = 5_000
                    connectTimeoutMillis = 4_000
                    socketTimeoutMillis = 4_000
                }
            }.execute { response ->
                if (response.status.value !in setOf(200, 206)) return@execute Probe("unavailable")
                val signature = ByteArray(4)
                response.bodyAsChannel().readFully(signature, 0, signature.size)
                if (signature.contentEquals(byteArrayOf(0x50, 0x4b, 0x03, 0x04))) {
                    Probe("available", SystemClock.elapsedRealtime() - started)
                } else Probe("unavailable")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: HttpRequestTimeoutException) {
            Probe("timeout")
        } catch (_: java.net.SocketTimeoutException) {
            Probe("timeout")
        } catch (_: IOException) {
            Probe("unavailable")
        }
    }

}
