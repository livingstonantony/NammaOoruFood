CREATE TABLE telegram_bot_state (
    id TINYINT PRIMARY KEY,
    next_offset BIGINT NULL,
    last_update_at DATETIME(3) NULL
);
INSERT INTO telegram_bot_state (id) VALUES (1);

CREATE TABLE telegram_outbox (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    order_id BIGINT NULL,
    order_status VARCHAR(32) NULL,
    update_id BIGINT NULL,
    message TEXT NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    lease_until DATETIME(3) NULL,
    lease_token VARCHAR(36) NULL,
    sent_at DATETIME(3) NULL,
    failed_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_telegram_outbox_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_telegram_outbox_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE,
    CONSTRAINT uq_telegram_outbox_order_status UNIQUE (order_id, order_status),
    CONSTRAINT uq_telegram_outbox_update UNIQUE (update_id),
    INDEX idx_telegram_outbox_pending (sent_at, failed_at, next_attempt_at, lease_until)
);
