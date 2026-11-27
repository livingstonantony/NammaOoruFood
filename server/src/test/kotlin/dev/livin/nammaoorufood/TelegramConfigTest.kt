package dev.livin.nammaoorufood

import dev.livin.nammaoorufood.config.AppConfig
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import dev.livin.nammaoorufood.telegram.telegramMessage

class TelegramConfigTest {
    @Test
    fun miniAppUrlMustBeHttpsAndMockIsRestrictedToLocalOrTest() {
        val env = mapOf("APP_ENV" to "local")
        val origin = "https://miniapp.example.com"
        assertEquals(origin, AppConfig.fromEnv(env + ("TELEGRAM_MINI_APP_URL" to origin)).telegramMiniAppUrl)
        assertEquals("$origin/?mock=true", AppConfig.fromEnv(env + ("TELEGRAM_MINI_APP_URL" to "$origin/?mock=true")).telegramMiniAppUrl)
        for (invalid in listOf("http://example.com", "https://user@example.com", "$origin/path", "$origin/?other=1", "$origin/#fragment")) {
            assertFailsWith<IllegalArgumentException> { AppConfig.fromEnv(env + ("TELEGRAM_MINI_APP_URL" to invalid)) }
        }
        val production = mapOf("APP_ENV" to "production", "JWT_SECRET" to "a".repeat(32),
            "DB_URL" to "jdbc:mysql://localhost/db", "DB_USER" to "user", "DB_PASSWORD" to "password")
        assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnv(production + ("TELEGRAM_MINI_APP_URL" to "$origin/?mock=true"))
        }
    }

    @Test
    fun telegramButtonUsesWebAppMarkup() {
        val json = Json { explicitNulls = false }
        val encoded = json.encodeToString(telegramMessage(12, "Welcome", "https://miniapp.example.com"))
        val markup = json.parseToJsonElement(encoded).jsonObject["reply_markup"]!!.jsonObject
        val button = markup["inline_keyboard"]!!.jsonArray[0].jsonArray[0].jsonObject
        assertEquals("Open Mini App", button["text"]!!.jsonPrimitive.content)
        assertEquals("https://miniapp.example.com", button["web_app"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        assertEquals(false, json.parseToJsonElement(json.encodeToString(telegramMessage(12, "Update", null))).jsonObject.containsKey("reply_markup"))
    }
    @Test
    fun tokenFileIsOptionalAndEnvironmentTakesPrecedence() {
        val file = Files.createTempFile("telegram-config-", ".env")
        try {
            Files.writeString(file, "TELEGRAM_BOT_TOKEN='123:file-token'\n")
            val env = mapOf("APP_ENV" to "test")
            assertEquals("123:file-token", AppConfig.fromSystemEnvironment(env, file).telegramBotToken)
            assertEquals("456:env-token", AppConfig.fromSystemEnvironment(env + ("TELEGRAM_BOT_TOKEN" to "456:env-token"), file).telegramBotToken)
            Files.writeString(file, "TELEGRAM_BOT_TOKEN=123:first\nTELEGRAM_BOT_TOKEN=123:second\n")
            assertFailsWith<IllegalArgumentException> { AppConfig.fromSystemEnvironment(env, file) }
            Files.writeString(file, "TELEGRAM_BOT_TOKEN=invalid\n")
            assertFailsWith<IllegalArgumentException> { AppConfig.fromSystemEnvironment(env, file) }
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
