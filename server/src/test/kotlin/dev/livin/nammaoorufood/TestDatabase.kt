package dev.livin.nammaoorufood

internal fun testDatabaseUrl(): String = System.getenv("SERVER_TEST_DB_URL")
    ?: "jdbc:mysql://localhost:3306/nammaoorufood_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
