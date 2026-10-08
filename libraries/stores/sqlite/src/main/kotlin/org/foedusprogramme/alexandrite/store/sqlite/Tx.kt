package org.foedusprogramme.alexandrite.store.sqlite

import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant

/** A transaction of the store, valid only inside its block. */
internal class Tx(
    private val connection: Connection,
    /** When the transaction began, to the millisecond. */
    val now: Instant,
) {
    private val committedActions = mutableListOf<() -> Unit>()

    /** Runs [sql] with [arguments] and returns how many rows it changed. */
    fun execute(sql: String, vararg arguments: Any?): Int = prepare(sql, arguments).use { it.executeUpdate() }

    fun <T> query(sql: String, vararg arguments: Any?, row: ResultSet.() -> T): List<T> =
        prepare(sql, arguments).use { statement ->
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.row()) } }
        }

    /** The first row [sql] yields, null when it yields none. */
    fun <T> queryOne(sql: String, vararg arguments: Any?, row: ResultSet.() -> T): T? =
        prepare(sql, arguments).use { statement ->
            statement.executeQuery().use { rows -> if (rows.next()) rows.row() else null }
        }

    /** Runs [action] once the transaction has committed. */
    fun afterCommit(action: () -> Unit) {
        committedActions += action
    }

    private fun prepare(sql: String, arguments: Array<out Any?>): PreparedStatement {
        val statement = connection.prepareStatement(sql)
        try {
            arguments.forEachIndexed { index, argument -> statement.bind(index + 1, argument) }
        } catch (e: Throwable) {
            statement.close()
            throw e
        }
        return statement
    }

    fun runAfterCommit() {
        for (action in committedActions) {
            try {
                action()
            } catch (e: Exception) {
                logger.warn("An action after a commit of the store failed", e)
            }
        }
    }
}

/** Runs [block] in a transaction of its own that it commits, or rolls back when [block] throws. */
internal fun <T> Connection.inTransaction(now: Instant, block: Tx.() -> T): T {
    val tx = Tx(this, now)
    runSql("BEGIN IMMEDIATE")
    val result = try {
        tx.block().also { runSql("COMMIT") }
    } catch (e: Throwable) {
        try {
            runSql("ROLLBACK")
        } catch (rollback: Exception) {
            e.addSuppressed(rollback)
        }
        throw e
    }
    tx.runAfterCommit()
    return result
}

internal fun Connection.runSql(sql: String) {
    createStatement().use { it.execute(sql) }
}

internal fun ResultSet.instant(column: String): Instant = Instant.ofEpochMilli(getLong(column))

internal fun ResultSet.instantOrNull(column: String): Instant? =
    getLong(column).takeUnless { wasNull() }?.let(Instant::ofEpochMilli)

private fun PreparedStatement.bind(index: Int, argument: Any?) {
    when (argument) {
        null -> setNull(index, Types.NULL)
        is String -> setString(index, argument)
        is Long -> setLong(index, argument)
        is Int -> setInt(index, argument)
        is Instant -> setLong(index, argument.toEpochMilli())
        else -> throw IllegalArgumentException("The store cannot bind a ${argument::class.qualifiedName}.")
    }
}

private val logger = LoggerFactory.getLogger(Tx::class.java)
