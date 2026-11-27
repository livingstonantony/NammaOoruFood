ALTER TABLE users MODIFY username VARCHAR(50) NULL, MODIFY password_hash VARCHAR(255) NULL;

CREATE TABLE telegram_identities (
    telegram_user_id BIGINT NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    telegram_username VARCHAR(255) NULL,
    private_chat_id BIGINT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uq_telegram_user UNIQUE (user_id),
    CONSTRAINT fk_telegram_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
