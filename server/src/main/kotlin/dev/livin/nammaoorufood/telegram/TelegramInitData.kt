package dev.livin.nammaoorufood.telegram

import dev.livin.nammaoorufood.plugins.UnauthorizedException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Serializable
private data class TelegramProfile(val id: Long, val first_name: String, val last_name: String? = null, val username: String? = null)

data class VerifiedTelegramUser internal constructor(val id: Long, val displayName: String, val username: String?)

class TelegramInitDataVerifier(private val botToken: String, private val clock: Clock = Clock.systemUTC()) {
    private val json = Json { ignoreUnknownKeys = true }

    fun verify(raw: String): VerifiedTelegramUser {
        if (raw.length !in 1..8192) throw UnauthorizedException("Invalid Telegram data")
        val fields = mutableMapOf<String, String>()
        try {
            for (part in raw.split('&')) {
                val pair = part.split('=', limit = 2)
                require(pair.size == 2)
                val key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8)
                val value = URLDecoder.decode(pair[1], StandardCharsets.UTF_8)
                require(fields.put(key, value) == null)
            }
        } catch (_: IllegalArgumentException) {
            throw UnauthorizedException("Invalid Telegram data")
        }
        val hash = fields.remove("hash") ?: throw UnauthorizedException("Invalid Telegram data")
        if (!hash.matches(Regex("^[0-9a-fA-F]{64}$"))) throw UnauthorizedException("Invalid Telegram data")
        val supplied = hash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val checkString = fields.toSortedMap().entries.joinToString("\n") { "${it.key}=${it.value}" }
        val secret = hmac("WebAppData".toByteArray(), botToken.toByteArray())
        val expected = hmac(secret, checkString.toByteArray())
        if (!MessageDigest.isEqual(expected, supplied)) throw UnauthorizedException("Invalid Telegram data")
        val timestamp = fields["auth_date"]?.toLongOrNull() ?: throw UnauthorizedException("Invalid Telegram data")
        val age = clock.instant().epochSecond - timestamp
        if (age !in 0..300) throw UnauthorizedException("Expired Telegram data")
        val profile = try {
            json.decodeFromString<TelegramProfile>(fields["user"] ?: throw UnauthorizedException("Invalid Telegram data"))
        } catch (_: kotlinx.serialization.SerializationException) {
            throw UnauthorizedException("Invalid Telegram data")
        } catch (_: IllegalArgumentException) {
            throw UnauthorizedException("Invalid Telegram data")
        }
        if (profile.id <= 0) throw UnauthorizedException("Invalid Telegram data")
        val name = listOfNotNull(profile.first_name, profile.last_name).joinToString(" ").trim().take(100)
        if (name.isEmpty()) throw UnauthorizedException("Invalid Telegram data")
        return VerifiedTelegramUser(profile.id, name, profile.username?.take(255))
    }

    private fun hmac(key: ByteArray, value: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(value)
        }
}
