package dev.pixelock.app.data.remote

import dev.pixelock.app.domain.BoardRow
import dev.pixelock.app.domain.DayUpload
import java.util.UUID

/** Debug builds with no server. Orders pay themselves a few seconds after they are made. */
object FakeBackend : Backend {

    private const val PAY_AFTER_MS = 4_000L
    private const val PAGE = "data:text/html,<h2>Fake payment</h2><p>This pays itself.</p>"

    private val orders = HashMap<String, Long>()
    private val consumed = HashSet<String>()
    private val mine = HashMap<String, DayUpload>()
    private var alias = "Me"

    override fun createGroup(alias: String): String {
        this.alias = alias
        return UUID.randomUUID().toString().filter { it in "23456789abcdef" }.take(16).padEnd(16, 'A').uppercase()
    }

    override fun joinGroup(code: String, alias: String) {
        if (code.startsWith("22")) throw BackendException("group_not_found")
        this.alias = alias
    }

    override fun leaveGroup(code: String) = Unit

    override fun board(code: String, from: String, to: String): List<BoardRow> {
        val days = mine.values.filter { it.date in from..to }
        val me = BoardRow(alias, days.sumOf { it.focusedMin.toLong() }, days.sumOf { it.distractedMin.toLong() }, true)
        return (listOf(me, BoardRow("Asha", 95, 12, false), BoardRow("Ravi", 40, 75, false)))
            .sortedByDescending { it.focusedMin }
    }

    override fun pushTotals(rows: List<DayUpload>) {
        rows.forEach { mine[it.date] = it }
    }

    override fun createPairCode(): String = "ABCD2345EFGH"

    override fun deleteMyData() = mine.clear()

    override fun createOrder(purpose: String): Order {
        val id = UUID.randomUUID().toString()
        orders[id] = System.currentTimeMillis()
        return Order(id, PAGE)
    }

    override fun orderStatus(id: String): String {
        val created = orders[id] ?: return "created"
        return when {
            id in consumed -> OrderStatus.CONSUMED
            System.currentTimeMillis() - created >= PAY_AFTER_MS -> OrderStatus.PAID
            else -> "created"
        }
    }

    override fun consumeOrder(id: String): Boolean = orderStatus(id) == OrderStatus.PAID && consumed.add(id)
}
