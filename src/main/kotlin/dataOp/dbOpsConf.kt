package com.apols.dataOp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.StdOutSqlLogger
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.UUIDTable
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.javatime.datetime
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.LocalDateTime
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.experimental.and

object Users: UUIDTable("userTable") {
    val name = varchar("user_name", 120)
    val password = varchar("pass", 10000)
    val email = varchar("email", 1000)
    val isAdmin = bool("isAdmin").default(false)
}
object Supports: UUIDTable("supportTable") {
    val createdBy = uuid("createdBy").references(Users.id)
}

object Messages: UUIDTable("messages") {
    val supportId = uuid("supportId").references(Supports.id)
    val content = text("messageContent")
    val reply = text("reply").nullable()
}

object PaymentTransactions : Table("payment_transactions") {
    val id = uuid("id").autoGenerate()
    val userId = uuid("user_id").references(Users.id)
    val reference = varchar("reference", 100).uniqueIndex()
    val amount = decimal("amount", 10, 2)
    val currency = varchar("currency", 3).default("NGN")
    val status = varchar("status", 20).default("pending")
    val paymentMethod = varchar("payment_method", 50).nullable()
    val paystackTransactionId = long("paystack_transaction_id").nullable()
    val paidAt = datetime("paid_at").nullable()
    val paidUntil = timestamp("paid_until").nullable()
    val createdAt = datetime("created_at").clientDefault { LocalDateTime.now() }
    override val primaryKey = PrimaryKey(id)
}
data class User(val id: UUID, val name: String, val password: String, val email: String, val isAdmin: Boolean = false)

data class UserInsert(val name: String, val password: String, val email: String)

suspend fun <T> dbQuery(block: () -> T): T =
    withContext(Dispatchers.IO) {
        transaction {
            addLogger(StdOutSqlLogger)
            block()
        }
    }

fun ResultRow.toUser() = User(
    id = this[Users.id].value,
    name = this[Users.name],
    password = this[Users.password],
    email = this[Users.email],
    isAdmin = this[Users.isAdmin]
)

fun getUser(name: String): User? {
    try {
        val user = transaction {
            Users.select(Users.name, Users.id, Users.name, Users.password)
                .single { it[Users.name] == name }.toUser()
        }
        return user
    } catch (e: ExposedSQLException) {
        e.printStackTrace()
        println("Exception caused by: ${e.cause} with context: ${e.contexts} and message: ${e.message}")
        return null
    }
}

fun insertUser(user: UserInsert) {
    transaction {
        val singedPassword = sing(user.password, user.name)
        val isNotAvaileble = Users.selectAll().where { Users.name eq user.name }.count() > 0
        if (isNotAvaileble) {
            Users.insert {
                it[name] = user.name
                it[password] = singedPassword
            }
        } else {
            return@transaction
        }
    }
}

fun sing(payload: String, key: String): String {
    val algo = "HmacShA256"
    return try {
        val mac = Mac.getInstance(algo)
        val sk = SecretKeySpec(key.toByteArray(), algo)
        mac.init(sk)
        val hash = mac.doFinal(payload.toByteArray())
        hash.joinToString("") { String.format("%02x", it.and(0xff.toByte())) }
    } catch (e: Exception) {
        e.printStackTrace()
    } as String
}

fun verifyUser(name: String, password: String): Boolean {
    val user = getUser(name)
    return user?.password.contentEquals(sing(password, name))
}