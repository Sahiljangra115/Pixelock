package dev.pixelock.app.data.remote

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import dev.pixelock.app.domain.BoardRow
import dev.pixelock.app.domain.DayUpload
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class AuthSession(val accessToken: String, val refreshToken: String, val expiresAtMs: Long)

interface TokenStore {
    var session: AuthSession?
}

/** App private and never backed up. Only ever identifies an anonymous device. */
class PrefsTokenStore(context: Context) : TokenStore {
    private val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)

    override var session: AuthSession?
        get() {
            val access = prefs.getString("access", null) ?: return null
            val refresh = prefs.getString("refresh", null) ?: return null
            return AuthSession(access, refresh, prefs.getLong("expires", 0L))
        }
        set(value) {
            prefs.edit()
                .putString("access", value?.accessToken)
                .putString("refresh", value?.refreshToken)
                .putLong("expires", value?.expiresAtMs ?: 0L)
                .apply()
        }
}

/** Talks to Supabase with plain HTTP. Identity is an anonymous sign in made on first use. */
class SupabaseBackend(
    private val baseUrl: String,
    private val anonKey: String,
    private val tokens: TokenStore,
    private val now: () -> Long = System::currentTimeMillis
) : Backend {

    private companion object {
        const val TIMEOUT_MS = 15_000
        const val EXPIRY_MARGIN_MS = 60_000L
        // Raised by the SQL functions. Anything else is reported as a plain server error.
        val KNOWN_ERRORS = setOf("group_not_found", "group_full", "too_many_groups", "not_a_member")
    }

    private class Reply(val status: Int, val body: String)

    override fun createGroup(alias: String, name: String): String = shaped {
        rpc("create_group", json("p_alias" to alias, "p_name" to name)).asString
    }

    override fun groupName(code: String): String = shaped {
        rpc("group_name", json("p_code" to code)).asString
    }

    override fun joinGroup(code: String, alias: String) {
        rpc("join_group", json("p_code" to code, "p_alias" to alias))
    }

    override fun leaveGroup(code: String) {
        rpc("leave_group", json("p_code" to code))
    }

    override fun board(code: String, from: String, to: String): List<BoardRow> = shaped {
        rpc("board", json("p_code" to code, "p_from" to from, "p_to" to to)).asJsonArray.map {
            val row = it.asJsonObject
            BoardRow(
                row["alias"].asString,
                row["focused_min"].asLong,
                row["distracted_min"].asLong,
                row["is_me"].asBoolean,
                row["phone_min"]?.takeIf { !it.isJsonNull }?.asLong ?: 0L,
                row["laptop_min"]?.takeIf { !it.isJsonNull }?.asLong ?: 0L
            )
        }
    }

    override fun pushTotals(rows: List<DayUpload>) {
        val body = JsonArray()
        rows.forEach {
            body.add(JsonObject().apply {
                addProperty("date", it.date)
                addProperty("focused_min", it.focusedMin)
                addProperty("distracted_min", it.distractedMin)
            })
        }
        authed(
            "POST",
            "/rest/v1/day_totals?on_conflict=user_id,date",
            body.toString(),
            mapOf("Prefer" to "resolution=merge-duplicates,return=minimal")
        )
    }

    override fun createPairCode(): String = shaped {
        rpc("create_pair_code", JsonObject()).asString
    }

    override fun deleteMyData() {
        rpc("delete_my_data", JsonObject())
    }

    override fun createOrder(purpose: String): Order = shaped {
        val reply = authed("POST", "/functions/v1/create-order", json("purpose" to purpose).toString())
        val order = parse(reply.body).asJsonObject
        Order(order["order_id"].asString, order["url"].asString)
    }

    override fun orderStatus(id: String): String = shaped {
        val query = "id=eq.${URLEncoder.encode(id, "UTF-8")}&select=status"
        val rows = parse(authed("GET", "/rest/v1/unlock_orders?$query", null).body).asJsonArray
        if (rows.size() != 1) throw BackendException(BackendErrors.SERVER)
        rows[0].asJsonObject["status"].asString
    }

    override fun consumeOrder(id: String): Boolean = shaped {
        val reply = rpc("consume_order", json("p_id" to id)).asJsonPrimitive
        if (!reply.isBoolean) throw BackendException(BackendErrors.SERVER)
        reply.asBoolean
    }

    /** A reply of the wrong shape, such as a Wi Fi login page, is an error and not a crash. */
    private inline fun <T> shaped(read: () -> T): T =
        try {
            read()
        } catch (e: IllegalStateException) {
            throw BackendException(BackendErrors.SERVER)
        } catch (e: UnsupportedOperationException) {
            throw BackendException(BackendErrors.SERVER)
        } catch (e: NullPointerException) {
            throw BackendException(BackendErrors.SERVER)
        } catch (e: ClassCastException) {
            throw BackendException(BackendErrors.SERVER)
        } catch (e: NumberFormatException) {
            throw BackendException(BackendErrors.SERVER)
        }

    private fun rpc(name: String, body: JsonObject): JsonElement =
        parse(authed("POST", "/rest/v1/rpc/$name", body.toString()).body)

    /** Sends with the current token. A token the server rejects is refreshed once. */
    private fun authed(method: String, path: String, body: String?, headers: Map<String, String> = emptyMap()): Reply {
        var reply = send(method, path, body, currentToken(), headers)
        if (reply.status == HttpURLConnection.HTTP_UNAUTHORIZED) {
            reply = send(method, path, body, refreshed(), headers)
        }
        if (reply.status >= 400) throw BackendException(errorCode(reply))
        return reply
    }

    @Synchronized
    private fun currentToken(): String {
        val session = tokens.session ?: return signUp()
        return if (session.expiresAtMs - now() > EXPIRY_MARGIN_MS) session.accessToken else refreshed()
    }

    private fun signUp(): String = storeSession(send("POST", "/auth/v1/signup", "{}", null))

    /** Refresh tokens work once, so two callers must never race here. */
    @Synchronized
    private fun refreshed(): String {
        val session = tokens.session ?: return signUp()
        val body = json("refresh_token" to session.refreshToken).toString()
        return storeSession(send("POST", "/auth/v1/token?grant_type=refresh_token", body, null))
    }

    private fun storeSession(reply: Reply): String {
        if (reply.status >= 400) throw BackendException(BackendErrors.AUTH)
        val auth = shaped { parse(reply.body).asJsonObject }
        val access = shaped { auth["access_token"].asString }
        val refresh = shaped { auth["refresh_token"].asString }
        val expiresIn = shaped { auth["expires_in"].asLong }
        tokens.session = AuthSession(access, refresh, now() + expiresIn * 1000)
        return access
    }

    private fun errorCode(reply: Reply): String {
        if (reply.status == HttpURLConnection.HTTP_UNAUTHORIZED) return BackendErrors.AUTH
        val message = runCatching { parse(reply.body).asJsonObject["message"]?.asString }.getOrNull()
        return message?.takeIf { it in KNOWN_ERRORS } ?: BackendErrors.SERVER
    }

    private fun parse(body: String): JsonElement =
        try {
            if (body.isBlank()) JsonNull.INSTANCE else JsonParser.parseString(body)
        } catch (_: JsonSyntaxException) {
            throw BackendException(BackendErrors.SERVER)
        }

    private fun json(vararg fields: Pair<String, String>) = JsonObject().apply {
        fields.forEach { (key, value) -> addProperty(key, value) }
    }

    /** With no [bearer] only the public key goes out. New style keys are not tokens, so never send one as a bearer. */
    private fun send(method: String, path: String, body: String?, bearer: String?, headers: Map<String, String> = emptyMap()): Reply {
        if (baseUrl.isBlank()) throw BackendException(BackendErrors.NOT_CONFIGURED)
        try {
            val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty("apikey", anonKey)
                if (bearer != null) connection.setRequestProperty("Authorization", "Bearer $bearer")
                headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(body.toByteArray()) }
                }
                val status = connection.responseCode
                val stream = if (status >= 400) connection.errorStream else connection.inputStream
                return Reply(status, stream?.use { it.readBytes().decodeToString() }.orEmpty())
            } finally {
                connection.disconnect()
            }
        } catch (_: IOException) {
            throw BackendException(BackendErrors.OFFLINE)
        }
    }
}
