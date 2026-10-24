CREATE TABLE notifications (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT       NOT NULL,
    order_id   BIGINT       NOT NULL,
    type       ENUM('ORDER_ACCEPTED', 'ORDER_REJECTED', 'ORDER_PREPARING', 'ORDER_READY') NOT NULL,
    title      VARCHAR(120) NOT NULL,
    message    VARCHAR(255) NOT NULL,
    read_at    DATETIME(3)  NULL,
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_notif_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_notif_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE,
    CONSTRAINT uq_notif_order_type UNIQUE (order_id, type),
    INDEX idx_notif_user (user_id, read_at, created_at)
);
