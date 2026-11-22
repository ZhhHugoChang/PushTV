package com.example.pushtv.network

import android.content.Context
import com.example.pushtv.data.GitHubProxyNode
import com.example.pushtv.data.GitHubProxySettings
import com.example.pushtv.data.SettingsRepo
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal fun Route.githubProxyRoutes(context: Context) {
    get("/api/github/proxy") {
        val settings = SettingsRepo.observeGitHubProxy(context).first()
        call.respondText(settings.toJson().toString(), ContentType.Application.Json)
    }

    post("/api/github/proxy") {
        if (!call.request.contentType().match(ContentType.Application.Json)) {
            call.respondText(
                """{"message":"请求必须使用 JSON"}""",
                ContentType.Application.Json, HttpStatusCode.UnsupportedMediaType
            )
            return@post
        }
        try {
            val raw = call.receiveText()
            require(raw.length <= 4096) { "代理设置请求过长" }
            val input = JSONObject(raw)
            when (input.getString("action")) {
                "select" -> SettingsRepo.selectGitHubProxy(context, input.getString("id"))
                "save" -> SettingsRepo.saveGitHubProxyNode(
                    context, input.optString("id").ifBlank { null }, input.getString("name"),
                    input.getString("prefix")
                )
                "delete" -> SettingsRepo.deleteGitHubProxyNode(context, input.getString("id"))
                "probe" -> {
                    val settings = SettingsRepo.observeGitHubProxy(context).first()
                    GitHubProxyClient.probeAll(settings.nodes)
                }
                "test" -> {
                    val prefix = GitHubProxySettings.normalizePrefix(input.getString("prefix"))
                    val result = GitHubProxyClient.probe(GitHubProxyNode("", "", prefix))
                    call.respondText(result.toJson().toString(), ContentType.Application.Json)
                    return@post
                }
                else -> throw IllegalArgumentException("无效的代理设置操作")
            }
            val settings = SettingsRepo.observeGitHubProxy(context).first()
            call.respondText(settings.toJson().toString(), ContentType.Application.Json)
        } catch (e: IllegalArgumentException) {
            call.respondText(
                JSONObject().put("message", e.message ?: "代理设置无效").toString(),
                ContentType.Application.Json, HttpStatusCode.BadRequest
            )
        } catch (_: JSONException) {
            call.respondText(
                """{"message":"代理设置格式无效"}""", ContentType.Application.Json, HttpStatusCode.BadRequest
            )
        }
    }
}

private fun GitHubProxySettings.toJson() = JSONObject().apply {
    put("selection", selection)
    put("nodes", JSONArray().apply {
        nodes.forEach { node ->
            put(JSONObject().put("id", node.id).put("name", node.name)
                .put("prefix", node.prefix)
                .put("custom", node in customNodes)
                .put("probe", GitHubProxyClient.recentProbe(node)?.toJson()))
        }
    })
}

private fun GitHubProxyClient.Probe.toJson() = JSONObject()
    .put("status", status).put("latencyMs", latencyMs).put("tone", tone)
