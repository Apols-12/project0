package com.apols.dataOp

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.UUIDTable
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.experimental.and

object UserTable: UUIDTable("userTable") {
    val name = varchar("user_name", 120)
    val password = varchar("pass", 10000)
}
data class User(val id: UUID, val name: String, val password: String)
//data class UserInsert(val name: String, val password: String)

fun ResultRow.toUser() = User(
    id = this[UserTable.id].value,
    name = this[UserTable.name],
    password = this[UserTable.password]
)
//fun getAllUsers(): List<User> {
//    return transaction {
//        UserTable
//            .selectAll()
//            .toList()
//            .map {
//                User(
//                    id = it[UserTable.id].value,
//                    name = it[UserTable.name],
//                    password = it[UserTable.password]
//                )
//            }
//
//    }
//}
fun getUser(name: String): User? {
    try {
        val user = transaction {
            UserTable.select(UserTable.name, UserTable.id, UserTable.name, UserTable.password)
                .single { it[UserTable.name] == name }.toUser()
        }
        return user
    } catch (e: ExposedSQLException) {
        e.printStackTrace()
        println("Exception caused by: ${e.cause} with context: ${e.contexts} and message: ${e.message}")
        return null
    }
}

//fun insertUser(user: UserInsert) {
//    transaction {
//        val singedPassword = sing(user.password, user.name)
//        val name = UserTable.insert {
//            it[name] = user.name
//            it[password] = singedPassword
//        } get UserTable.name
//        println(name)
//    }
//}

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