package app.diskscan

import org.json.JSONObject

class EngineException(message: String) : Exception(message)

/**
 * The native core (libdiskscan.so). One JNI call carries an endpoint name and JSON both ways;
 * text crosses as UTF-8 bytes because JNI's own string conversion crashes on emoji file names.
 */
object Engine {
    init {
        System.loadLibrary("diskscan")
    }

    @JvmStatic private external fun nativeInit(volumes: Array<ByteArray>)

    @JvmStatic private external fun nativeCall(method: ByteArray, endpoint: ByteArray, query: ByteArray, body: ByteArray): ByteArray

    /** Storage roots the user can scan, as (path, label). */
    fun init(volumes: List<Pair<String, String>>) =
        nativeInit(volumes.map { "${it.first}\t${it.second}".toByteArray() }.toTypedArray())

    fun get(endpoint: String, query: String = ""): JSONObject = call("GET", endpoint, query, "")

    fun post(endpoint: String, body: JSONObject = JSONObject()): JSONObject = call("POST", endpoint, "", body.toString())

    private fun call(method: String, endpoint: String, query: String, body: String): JSONObject {
        val raw = String(
            nativeCall(method.toByteArray(), endpoint.toByteArray(), query.toByteArray(), body.toByteArray()),
            Charsets.UTF_8,
        )
        val newline = raw.indexOf('\n')
        val status = raw.substring(0, newline).toInt()
        val json = JSONObject(raw.substring(newline + 1))
        if (status != 200) throw EngineException(json.optString("error", "error $status"))
        return json
    }
}
