package com.jimmy.btdeploy.api

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class BtApiException(message: String) : Exception(message)

/** 宝塔面板 API 客户端，接口与桌面版保持一致。 */
class BtClient(
    panelUrl: String,
    apiSk: String,
    private val verifySsl: Boolean = false,
    private val timeoutMs: Int = 600_000,
) {
    private val base = normalizeUrl(panelUrl)
    private val apiSk = apiSk.trim().ifEmpty { throw BtApiException("API 密钥不能为空") }

    fun testConnection() {
        postForm("/files", mapOf("action" to "GetDir", "path" to "/tmp"))
    }

    fun ensureDir(path: String) {
        try {
            postForm("/files", mapOf("action" to "CreateDir", "path" to path))
        } catch (_: BtApiException) {
            // 老面板没有 CreateDir，上传时会自行建目录
        }
    }

    fun uploadFile(remoteDir: String, filename: String, bytes: ByteArray) {
        postMultipart(
            "/files",
            mapOf("action" to "UploadFile", "path" to remoteDir) + sign(),
            "zunfile",
            filename,
            bytes,
        )
    }

    fun unzip(remoteZip: String, destDir: String): JsonElement = postForm(
        "/files",
        mapOf(
            "action" to "mutil_unzip",
            "sfile_list" to Gson().toJson(listOf(remoteZip)),
            "dfile" to destDir,
            "coding" to "utf-8",
            "type1" to "zip",
        ),
    )

    fun delete(path: String) {
        postForm("/files", mapOf("action" to "DeleteDir", "path" to path))
    }

    fun serviceAdmin(name: String, op: String) {
        postForm("/system", mapOf("action" to "ServiceAdmin", "name" to name, "type" to op))
    }

    fun javaProjectAction(projectName: String, op: String = "restart") {
        val path = "/mod/java/project/${op}_project/stype"
        val params = mapOf("project_name" to projectName)
        try {
            get(path, params)
        } catch (_: BtApiException) {
            postForm(path, params)
        }
    }

    /** 面板上的网站：每项的名称和网站根目录。 */
    fun listSites(): List<SiteInfo> {
        val data = postForm("/data", mapOf("action" to "getData", "table" to "sites", "type" to "-1"))
        return rowsOf(data).mapNotNull { row ->
            val obj = row as? JsonObject ?: return@mapNotNull null
            val path = obj.string("path")
            if (path.isBlank()) null else SiteInfo(obj.string("name"), path)
        }.distinctBy { it.path }.sortedBy { it.name }
    }

    /** GetConcifInfo：各组件版本与运行状态，用于卡片上的状态灯。 */
    fun configInfo(): JsonElement = postForm("/system", mapOf("action" to "GetConcifInfo"))

    /** 面板上的 Java 项目：名称、项目目录和运行状态，用于部署配置和状态灯。 */
    fun listJavaProjects(): List<JavaProjectInfo> {
        val endpoint = "/mod/java/project/project_list/stype"
        val data = try {
            get(endpoint, emptyMap())
        } catch (_: BtApiException) {
            postForm(endpoint, emptyMap())
        }
        return rowsOf(data).mapNotNull { row ->
            when {
                row.isJsonObject -> {
                    val obj = row.asJsonObject
                    val name = obj.string("name").ifBlank { obj.string("project_name") }
                    if (name.isBlank()) null else JavaProjectInfo(name, javaPathOf(obj), javaRunningOf(obj))
                }

                row.isJsonPrimitive -> {
                    val name = row.asString.split(";").firstOrNull().orEmpty()
                    if (name.isBlank()) null else JavaProjectInfo(name, "", null)
                }

                else -> null
            }
        }.distinctBy { it.name }.sortedBy { it.name }
    }

    /** GetDir 的文件列表。有的面板返回对象数组，有的返回 `名称;大小;时间` 分号串。 */
    fun listDir(path: String): List<RemoteFile> {
        val data = postForm("/files", mapOf("action" to "GetDir", "path" to path))
        val rows = when {
            data is JsonArray -> data.toList()
            data is JsonObject -> data.get("FILES")?.let(::rowsOf).orEmpty()
            else -> emptyList()
        }
        return rows.mapNotNull { row ->
            if (row.isJsonObject) {
                val obj = row.asJsonObject
                val name = obj.string("filename")
                RemoteFile(name, obj.long("size"), obj.long("mtime"))
            } else {
                val parts = row.asString.split(";")
                RemoteFile(
                    parts.getOrNull(0).orEmpty(),
                    parts.getOrNull(1)?.toLongOrNull() ?: 0,
                    parts.getOrNull(2)?.toLongOrNull() ?: 0,
                )
            }
        }.filter { it.name.isNotEmpty() }
    }

    // ------------------------------------------------------------------ 签名 / 请求

    fun sign(): Map<String, String> {
        val time = (System.currentTimeMillis() / 1000).toString()
        return mapOf(
            "request_time" to time,
            "request_token" to md5(time + md5(apiSk)),
        )
    }

    private fun postForm(path: String, params: Map<String, String>): JsonElement {
        val body = encode(params + sign()).toByteArray()
        val conn = open(path, "POST")
        return try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(body) }
            readJson(conn)
        } catch (e: IOException) {
            throw BtApiException("无法连接面板（$base）：${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    private fun get(path: String, params: Map<String, String>): JsonElement {
        val conn = open(path, "GET", params + sign())
        return try {
            readJson(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun postMultipart(
        path: String,
        fields: Map<String, String>,
        fileField: String,
        filename: String,
        bytes: ByteArray,
    ): JsonElement {
        val boundary = "----btdeploy" + UUID.randomUUID().toString().replace("-", "")
        val buf = ByteArrayOutputStream()
        val crlf = "\r\n".toByteArray()

        for ((key, value) in fields) {
            buf.write("--$boundary\r\n".toByteArray())
            buf.write("Content-Disposition: form-data; name=\"$key\"\r\n\r\n".toByteArray())
            buf.write(value.toByteArray())
            buf.write(crlf)
        }
        buf.write("--$boundary\r\n".toByteArray())
        buf.write(
            "Content-Disposition: form-data; name=\"$fileField\"; filename=\"$filename\"\r\n".toByteArray()
        )
        buf.write("Content-Type: application/octet-stream\r\n\r\n".toByteArray())
        buf.write(bytes)
        buf.write(crlf)
        buf.write("--$boundary--\r\n".toByteArray())

        val payload = buf.toByteArray()
        val conn = open(path, "POST")
        return try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.setFixedLengthStreamingMode(payload.size.toLong())
            conn.outputStream.use { it.write(payload) }
            readJson(conn)
        } catch (e: IOException) {
            throw BtApiException("上传失败（$base）：${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    private fun open(path: String, method: String, query: Map<String, String>? = null): HttpURLConnection {
        val suffix = query?.let { "?" + encode(it) }.orEmpty()
        val url = try {
            URI(base + path + suffix).toURL()
        } catch (e: Exception) {
            throw BtApiException("面板地址格式有误：${e.message}")
        }
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 30_000
        conn.readTimeout = timeoutMs
        if (!verifySsl && conn is HttpsURLConnection) {
            conn.sslSocketFactory = trustAllContext.socketFactory
            conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
        }
        return conn
    }

    private fun readJson(conn: HttpURLConnection): JsonElement {
        val code = try {
            conn.responseCode
        } catch (e: IOException) {
            throw BtApiException("无法连接面板（$base）：${e.message}")
        }
        val text = try {
            (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?.trim()
                .orEmpty()
        } catch (e: IOException) {
            throw BtApiException("读取面板响应失败：${e.message}")
        }
        if (code !in 200..299) {
            throw BtApiException("HTTP $code：${text.take(300)}")
        }

        val result = try {
            JsonParser.parseString(text)
        } catch (_: Exception) {
            throw BtApiException("面板返回了非 JSON 内容，请检查地址/端口/协议：${text.take(200)}")
        }
        if (result is JsonObject && result.get("status")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && !it.asBoolean } == true) {
            throw BtApiException(result.string("msg").ifEmpty { "面板返回失败（未给出原因）" })
        }
        return result
    }

    // ------------------------------------------------------------------ 工具

    private fun rowsOf(element: JsonElement): List<JsonElement> = when {
        element.isJsonArray -> element.asJsonArray.toList()
        element.isJsonObject -> element.asJsonObject.get("data")
            ?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty()

        else -> emptyList()
    }

    private fun JsonObject.string(key: String): String =
        get(key)?.takeIf { !it.isJsonNull }?.asString.orEmpty()

    private fun JsonObject.long(key: String): Long =
        get(key)?.takeIf { !it.isJsonNull }?.asLong ?: 0

    private fun javaPathOf(obj: JsonObject): String {
        var raw = JAVA_PATH_KEYS.firstNotNullOfOrNull { key ->
            obj.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
        }.orEmpty()
        if (raw.isEmpty()) {
            raw = Regex("""\S+\.jar""").find(obj.string("project_cmd"))?.value.orEmpty()
        }
        return if (raw.endsWith(".jar")) raw.substringBeforeLast('/', "") else raw
    }

    /** Java 项目运行状态：优先看 pid / pid_info，旧面板才退回 status。 */
    private fun javaRunningOf(obj: JsonObject): Boolean? {
        for (key in listOf("pid", "pid_info")) {
            if (!obj.has(key)) continue
            val value = obj.get(key)
            if (value == null || value.isJsonNull) return false
            if (value.isJsonPrimitive) {
                val primitive = value.asJsonPrimitive
                if (primitive.isBoolean) return primitive.asBoolean
                if (primitive.isNumber) return primitive.asInt > 0
                val text = primitive.asString.trim().lowercase()
                if (text.isEmpty() || text in STOPPED_TEXTS) return false
                val pids = Regex("""\d+""").findAll(text).map { it.value.toInt() }.toList()
                return if (pids.isNotEmpty()) pids.any { it > 0 } else null
            }
            return true
        }
        return obj.get("status")?.toRunning()
    }

    private fun JsonElement.toRunning(): Boolean? {
        if (!isJsonPrimitive) return null
        val primitive = asJsonPrimitive
        if (primitive.isBoolean) return primitive.asBoolean
        if (primitive.isNumber) return primitive.asInt != 0
        return when (primitive.asString.trim().lowercase()) {
            "1", "true", "on", "running", "start", "started" -> true
            "0", "false", "off", "stopped", "stop", "notrunning" -> false
            else -> null
        }
    }

    private fun encode(params: Map<String, String>): String =
        params.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }

    companion object {
        private val JAVA_PATH_KEYS = listOf(
            "project_jar", "jar_path", "jar", "project_path", "path",
            "root", "project_root", "run_path", "work_dir", "workdir", "home",
        )

        private val STOPPED_TEXTS = setOf(
            "", "none", "null", "0", "false", "stopped", "stop", "notrunning", "not running",
        )

        private fun normalizeUrl(url: String): String {
            var value = url.trim()
            if (value.isEmpty()) throw BtApiException("面板地址不能为空")
            if (!value.startsWith("http://") && !value.startsWith("https://")) {
                value = "https://$value"
            }
            return value.trimEnd('/')
        }

        private fun md5(text: String): String =
            MessageDigest.getInstance("MD5").digest(text.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }

        private val trustAllContext: SSLContext by lazy {
            SSLContext.getInstance("TLS").apply {
                init(
                    null,
                    arrayOf<TrustManager>(object : X509TrustManager {
                        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                    }),
                    SecureRandom(),
                )
            }
        }
    }
}

data class RemoteFile(val name: String, val size: Long, val mtime: Long)

data class SiteInfo(val name: String, val path: String)

data class JavaProjectInfo(val name: String, val path: String, val running: Boolean?)
