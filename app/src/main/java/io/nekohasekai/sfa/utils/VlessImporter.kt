package io.nekohasekai.sfa.utils

import android.content.Context
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.database.TypedProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URLDecoder

object VlessImporter {

    suspend fun import(context: Context, vlessUri: String): Profile = withContext(Dispatchers.IO) {
        val parsed = parseVlessUri(vlessUri)
        val config = generateConfig(parsed)
        val configJson = config.toString(2)

        Libbox.checkConfig(configJson)

        val configDirectory = File(context.filesDir, "configs").also { it.mkdirs() }
        val fileID = ProfileManager.nextFileID()
        val configFile = File(configDirectory, "$fileID.json")
        configFile.writeText(configJson)

        val typedProfile = TypedProfile().apply {
            path = configFile.path
            type = TypedProfile.Type.Local
        }
        val profile = Profile(
            name = parsed.name.ifEmpty { "${parsed.server}:${parsed.port}" },
            typed = typedProfile,
            userOrder = ProfileManager.nextOrder()
        )
        val created = ProfileManager.create(profile)
        Settings.selectedProfile = created.id
        created
    }

    private data class VlessParams(
        val uuid: String,
        val server: String,
        val port: Int,
        val security: String,
        val sni: String,
        val pbk: String,
        val sid: String,
        val fp: String,
        val flow: String,
        val type: String,
        val name: String
    )

    private fun parseVlessUri(uri: String): VlessParams {
        require(uri.startsWith("vless://")) { "Not a VLESS URI" }

        // vless://UUID@server:port?params#name
        val parsed = URI(uri)
        val uuid = parsed.userInfo ?: error("Missing UUID")
        val server = parsed.host ?: error("Missing server")
        val port = parsed.port.takeIf { it > 0 } ?: 443

        val queryParams = mutableMapOf<String, String>()
        parsed.rawQuery?.split("&")?.forEach { param ->
            val parts = param.split("=", limit = 2)
            if (parts.size == 2) {
                queryParams[parts[0]] = URLDecoder.decode(parts[1], "UTF-8")
            }
        }

        val fragment = parsed.rawFragment?.let { URLDecoder.decode(it, "UTF-8") } ?: ""

        return VlessParams(
            uuid = uuid,
            server = server,
            port = port,
            security = queryParams["security"] ?: "",
            sni = queryParams["sni"] ?: "",
            pbk = queryParams["pbk"] ?: "",
            sid = queryParams["sid"] ?: "",
            fp = queryParams["fp"] ?: "firefox",
            flow = queryParams["flow"] ?: "",
            type = queryParams["type"] ?: "tcp",
            name = fragment
        )
    }

    private fun generateConfig(params: VlessParams): JSONObject {
        val config = JSONObject()

        // Log
        config.put("log", JSONObject().put("level", "info"))

        // DNS
        config.put("dns", JSONObject().apply {
            put("servers", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "tls")
                    put("tag", "remote")
                    put("server", "8.8.8.8")
                    put("server_port", 853)
                })
                put(JSONObject().apply {
                    put("type", "local")
                    put("tag", "local")
                })
            })
            put("final", "remote")
        })

        // Inbounds
        config.put("inbounds", JSONArray().apply {
            put(JSONObject().apply {
                put("type", "tun")
                put("tag", "tun-in")
                put("address", JSONArray().apply {
                    put("172.19.0.1/30")
                    put("fdfe:dcba:9876::1/126")
                })
                put("auto_route", true)
                put("strict_route", true)
                put("sniff", true)
            })
        })

        // Outbounds
        config.put("outbounds", JSONArray().apply {
            put(JSONObject().apply {
                put("type", "vless")
                put("tag", "proxy")
                put("server", params.server)
                put("server_port", params.port)
                put("uuid", params.uuid)
                if (params.flow.isNotEmpty()) {
                    put("flow", params.flow)
                }
                // TLS
                val tls = JSONObject().apply {
                    put("enabled", true)
                    if (params.sni.isNotEmpty()) {
                        put("server_name", params.sni)
                    }
                    // uTLS
                    put("utls", JSONObject().apply {
                        put("enabled", true)
                        put("fingerprint", params.fp)
                    })
                    // Reality
                    if (params.security == "reality") {
                        put("reality", JSONObject().apply {
                            put("enabled", true)
                            if (params.pbk.isNotEmpty()) {
                                put("public_key", params.pbk)
                            }
                            if (params.sid.isNotEmpty()) {
                                put("short_id", params.sid)
                            }
                        })
                    }
                }
                put("tls", tls)
            })
            put(JSONObject().apply {
                put("type", "direct")
                put("tag", "direct")
            })
        })

        // Route
        config.put("route", JSONObject().apply {
            put("rules", JSONArray().apply {
                put(JSONObject().apply {
                    put("protocol", "dns")
                    put("action", "hijack-dns")
                })
                put(JSONObject().apply {
                    put("ip_is_private", true)
                    put("action", "route")
                    put("outbound", "direct")
                })
            })
            put("auto_detect_interface", true)
            put("default_domain_resolver", "local")
        })

        return config
    }
}
