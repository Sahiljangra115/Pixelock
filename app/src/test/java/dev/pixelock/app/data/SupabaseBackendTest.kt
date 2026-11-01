package dev.pixelock.app.data

import dev.pixelock.app.data.remote.AuthSession
import dev.pixelock.app.data.remote.BackendException
import dev.pixelock.app.data.remote.SupabaseBackend
import dev.pixelock.app.data.remote.TokenStore
import dev.pixelock.app.domain.BoardRow
import dev.pixelock.app.domain.DayUpload
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** Checks the wire format against a stub that answers the way Supabase does. */
class SupabaseBackendTest {

    private class Seen(val method: String, val uri: String, val body: String, val auth: String?, val apikey: String?, val prefer: String?)

    private class MemoryTokens : TokenStore {
        override var session: AuthSession? = null
    }

    private lateinit var server: ServerSocket
    private val seen = ArrayList<Seen>()
    private val replies = HashMap<String, Pair<Int, String>>()
    private val tokens = MemoryTokens()
    private var clock = 1_000_000L
    private lateinit var backend: SupabaseBackend

    private val signedUp = """{"access_token":"a1","refresh_token":"r1","expires_in":3600}"""

    @Before fun start() {
        server = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use(::answer)
            }
        }
        replies["/auth/v1/signup"] = 200 to signedUp
        backend = SupabaseBackend("http://127.0.0.1:${server.localPort}", "anon", tokens) { clock }
    }

    @After fun stop() = server.close()

    private fun answer(socket: Socket) {
        val input = socket.getInputStream().buffered()
        fun line(): String = generateSequence { input.read().takeIf { it != -1 } }
            .takeWhile { it != '\n'.code }.map { it.toChar() }.joinToString("").trimEnd('\r')

        val (method, uri) = line().split(' ')
        val headers = generateSequence { line().takeIf { it.isNotEmpty() } }
            .associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
        val body = ByteArray(headers["content-length"]?.toInt() ?: 0) { input.read().toByte() }
        seen += Seen(method, uri, body.decodeToString(), headers["authorization"], headers["apikey"], headers["prefer"])

        val (status, reply) = replies[uri.substringBefore('?')] ?: (200 to "")
        val bytes = reply.toByteArray()
        socket.getOutputStream().apply {
            write("HTTP/1.1 $status X\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(bytes)
            flush()
        }
    }

    private fun assertFails(code: String, action: () -> Unit) {
        try {
            action()
            fail("expected $code")
        } catch (e: BackendException) {
            assertEquals(code, e.code)
        }
    }

    @Test fun `first call signs in anonymously and then reuses the token`() {
        replies["/rest/v1/rpc/create_group"] = 200 to "\"23456789ABCDEFGH\""
        assertEquals("23456789ABCDEFGH", backend.createGroup("Sam"))
        backend.leaveGroup("23456789ABCDEFGH")

        assertEquals("/auth/v1/signup", seen[0].uri)
        assertEquals(null, seen[0].auth)
        assertEquals("anon", seen[0].apikey)
        assertEquals("{\"p_alias\":\"Sam\"}", seen[1].body)
        assertEquals("Bearer a1", seen[1].auth)
        assertEquals("anon", seen[1].apikey)
        assertEquals(1, seen.count { it.uri == "/auth/v1/signup" })
    }

    @Test fun `an expired token is refreshed before the call`() {
        tokens.session = AuthSession("old", "r0", clock + 10_000)
        replies["/auth/v1/token"] = 200 to """{"access_token":"a2","refresh_token":"r2","expires_in":3600}"""
        backend.deleteMyData()

        assertEquals("/auth/v1/token?grant_type=refresh_token", seen[0].uri)
        assertEquals("{\"refresh_token\":\"r0\"}", seen[0].body)
        assertEquals("Bearer a2", seen[1].auth)
        assertEquals("r2", tokens.session?.refreshToken)
    }

    @Test fun `a token the server rejects is refreshed once and the call is sent again`() {
        tokens.session = AuthSession("bad", "r0", clock + 3_600_000)
        replies["/rest/v1/rpc/delete_my_data"] = 401 to "{}"
        replies["/auth/v1/token"] = 200 to """{"access_token":"a2","refresh_token":"r2","expires_in":3600}"""
        assertFails("auth") { backend.deleteMyData() }
        assertEquals(listOf("Bearer bad", null, "Bearer a2"), seen.map { it.auth })
    }

    @Test fun `board reads the phone and laptop split`() {
        tokens.session = AuthSession("t", "r", clock + 3_600_000)
        replies["/rest/v1/rpc/board"] = 200 to
            """[{"alias":"Sam","focused_min":90,"distracted_min":5,"is_me":true,"phone_min":60,"laptop_min":30,"laptop_distracted_min":2}]"""
        assertEquals(listOf(BoardRow("Sam", 90, 5, true, 60, 30)), backend.board("23456789ABCDEFGH", "2026-10-01", "2026-10-30"))
    }

    @Test fun `pair code is read from the rpc`() {
        tokens.session = AuthSession("t", "r", clock + 3_600_000)
        replies["/rest/v1/rpc/create_pair_code"] = 200 to "\"ABCD2345EFGH\""
        assertEquals("ABCD2345EFGH", backend.createPairCode())
    }

    @Test fun `board reads aliases and totals`() {
        tokens.session = AuthSession("t", "r", clock + 3_600_000)
        replies["/rest/v1/rpc/board"] = 200 to
            """[{"alias":"Sam","focused_min":90,"distracted_min":5,"is_me":true}]"""
        assertEquals(listOf(BoardRow("Sam", 90, 5, true)), backend.board("23456789ABCDEFGH", "2026-10-01", "2026-10-30"))
        assertEquals(
            """{"p_code":"23456789ABCDEFGH","p_from":"2026-10-01","p_to":"2026-10-30"}""",
            seen.last().body
        )
    }

    @Test fun `upload merges by day and carries only date and two numbers`() {
        tokens.session = AuthSession("t", "r", clock + 3_600_000)
        backend.pushTotals(listOf(DayUpload("2026-10-30", 12, 3)))
        val call = seen.last()
        assertEquals("/rest/v1/day_totals?on_conflict=user_id,date", call.uri)
        assertEquals("""[{"date":"2026-10-30","focused_min":12,"distracted_min":3}]""", call.body)
        assertTrue(call.prefer!!.contains("resolution=merge-duplicates"))
    }

    @Test fun `database errors keep their name and others stay generic`() {
        tokens.session = AuthSession("t", "r", clock + 3_600_000)
        replies["/rest/v1/rpc/join_group"] = 400 to """{"code":"P0001","message":"group_not_found"}"""
        assertFails("group_not_found") { backend.joinGroup("23456789ABCDEFGH", "Sam") }
        replies["/rest/v1/rpc/join_group"] = 400 to """{"message":"secret server detail"}"""
        assertFails("server") { backend.joinGroup("23456789ABCDEFGH", "Sam") }
        replies["/rest/v1/rpc/join_group"] = 502 to "<html>not json</html>"
        assertFails("server") { backend.joinGroup("23456789ABCDEFGH", "Sam") }
    }

    @Test fun `order flow matches the edge function and the database`() {
        tokens.session = AuthSession("t", "r", clock + 3_600_000)
        replies["/functions/v1/create-order"] = 200 to
            """{"order_id":"id1","url":"https://rzp.io/i/x","amount_paise":2000}"""
        val order = backend.createOrder("unlock_app")
        assertEquals("id1", order.id)
        assertEquals("https://rzp.io/i/x", order.url)
        assertEquals("""{"purpose":"unlock_app"}""", seen.last().body)

        replies["/rest/v1/unlock_orders"] = 200 to """[{"status":"paid"}]"""
        assertEquals("paid", backend.orderStatus("id1"))
        assertEquals("/rest/v1/unlock_orders?id=eq.id1&select=status", seen.last().uri)

        replies["/rest/v1/rpc/consume_order"] = 200 to "true"
        assertTrue(backend.consumeOrder("id1"))
        replies["/rest/v1/rpc/consume_order"] = 200 to "false"
        assertFalse(backend.consumeOrder("id1"))
    }

    @Test fun `an order that is not found is an error, never paid`() {
        tokens.session = AuthSession("t", "r", clock + 3_600_000)
        replies["/rest/v1/unlock_orders"] = 200 to "[]"
        assertFails("server") { backend.orderStatus("nope") }
    }

    @Test fun `a reply of the wrong shape is an error, not a crash`() {
        tokens.session = AuthSession("t", "r", clock + 3_600_000)
        replies["/rest/v1/rpc/board"] = 200 to """{"not":"a list"}"""
        assertFails("server") { backend.board("x", "a", "b") }
        replies["/rest/v1/rpc/board"] = 200 to """[{"alias":null}]"""
        assertFails("server") { backend.board("x", "a", "b") }
        replies["/functions/v1/create-order"] = 200 to "null"
        assertFails("server") { backend.createOrder("unlock_app") }
        replies["/rest/v1/rpc/consume_order"] = 200 to "<html>"
        assertFails("server") { backend.consumeOrder("id") }
    }

    @Test fun `a sign in reply with no tokens is refused`() {
        replies["/auth/v1/signup"] = 200 to """{"user":{}}"""
        assertFails("server") { backend.deleteMyData() }
    }

    @Test fun `no server and no network are plain errors`() {
        assertFails("not_configured") { SupabaseBackend("", "k", tokens).deleteMyData() }
        assertFails("offline") { SupabaseBackend("http://127.0.0.1:1", "k", tokens).deleteMyData() }
    }
}
