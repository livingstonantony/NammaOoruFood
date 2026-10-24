-- All timestamps are stored in UTC. Money uses DECIMAL(10,2).

CREATE TABLE users (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(50)  NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role          ENUM('CUSTOMER', 'HOTEL_OWNER', 'ADMIN') NOT NULL,
    display_name  VARCHAR(100) NOT NULL,
    phone         VARCHAR(20)  NULL,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT uq_users_username UNIQUE (username)
);

CREATE TABLE auth_sessions (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id            BIGINT      NOT NULL,
    refresh_token_hash CHAR(64)    NOT NULL,
    expires_at         DATETIME(3) NOT NULL,
    revoked_at         DATETIME(3) NULL,
    created_at         DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uq_sessions_refresh_hash UNIQUE (refresh_token_hash),
    CONSTRAINT fk_sessions_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    INDEX idx_sessions_user (user_id)
);

CREATE TABLE hotels (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    owner_id         BIGINT       NOT NULL,
    name             VARCHAR(120) NOT NULL,
    description      VARCHAR(500) NULL,
    address          VARCHAR(255) NOT NULL,
    phone            VARCHAR(20)  NULL,
    approval_status  ENUM('PENDING', 'APPROVED', 'REJECTED', 'SUSPENDED') NOT NULL DEFAULT 'PENDING',
    accepting_orders BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_hotels_owner FOREIGN KEY (owner_id) REFERENCES users (id),
    INDEX idx_hotels_owner (owner_id),
    INDEX idx_hotels_listing (approval_status, accepting_orders)
);

CREATE TABLE hotel_operating_hours (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    hotel_id    BIGINT   NOT NULL,
    day_of_week TINYINT  NOT NULL, -- 1 = Monday ... 7 = Sunday
    opens_at    TIME     NOT NULL,
    closes_at   TIME     NOT NULL, -- may be earlier than opens_at for overnight service
    CONSTRAINT fk_hours_hotel FOREIGN KEY (hotel_id) REFERENCES hotels (id) ON DELETE CASCADE,
    CONSTRAINT uq_hours_hotel_day UNIQUE (hotel_id, day_of_week),
    CONSTRAINT chk_hours_day CHECK (day_of_week BETWEEN 1 AND 7)
);

CREATE TABLE menu_categories (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    hotel_id   BIGINT       NOT NULL,
    name       VARCHAR(80)  NOT NULL,
    sort_order INT          NOT NULL DEFAULT 0,
    CONSTRAINT fk_categories_hotel FOREIGN KEY (hotel_id) REFERENCES hotels (id) ON DELETE CASCADE,
    CONSTRAINT uq_categories_hotel_name UNIQUE (hotel_id, name)
);

CREATE TABLE food_items (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    hotel_id    BIGINT        NOT NULL,
    category_id BIGINT        NULL,
    name        VARCHAR(120)  NOT NULL,
    description VARCHAR(500)  NULL,
    price       DECIMAL(10,2) NOT NULL,
    available   BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_food_hotel FOREIGN KEY (hotel_id) REFERENCES hotels (id) ON DELETE CASCADE,
    CONSTRAINT fk_food_category FOREIGN KEY (category_id) REFERENCES menu_categories (id) ON DELETE SET NULL,
    CONSTRAINT chk_food_price CHECK (price >= 0),
    INDEX idx_food_hotel_available (hotel_id, available)
);

CREATE TABLE orders (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id BIGINT        NOT NULL,
    hotel_id    BIGINT        NOT NULL,
    status      ENUM('PLACED', 'ACCEPTED', 'PREPARING', 'READY_FOR_PICKUP', 'COMPLETED', 'REJECTED') NOT NULL DEFAULT 'PLACED',
    total       DECIMAL(10,2) NOT NULL,
    note        VARCHAR(255)  NULL,
    idempotency_key VARCHAR(64) NULL,
    created_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES users (id),
    CONSTRAINT fk_orders_hotel FOREIGN KEY (hotel_id) REFERENCES hotels (id),
    CONSTRAINT uq_orders_idempotency UNIQUE (customer_id, idempotency_key),
    INDEX idx_orders_customer (customer_id, created_at),
    INDEX idx_orders_hotel_status (hotel_id, status, created_at)
);

-- Name and unit price are snapshots taken at order time.
CREATE TABLE order_items (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id     BIGINT        NOT NULL,
    food_item_id BIGINT        NULL,
    item_name    VARCHAR(120)  NOT NULL,
    unit_price   DECIMAL(10,2) NOT NULL,
    quantity     INT           NOT NULL,
    line_total   DECIMAL(10,2) NOT NULL,
    CONSTRAINT fk_items_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE,
    CONSTRAINT fk_items_food FOREIGN KEY (food_item_id) REFERENCES food_items (id) ON DELETE SET NULL,
    CONSTRAINT chk_items_qty CHECK (quantity > 0),
    INDEX idx_items_order (order_id)
);

-- Token numbering (default assumption): sequential per hotel per business day.
CREATE TABLE hotel_token_counters (
    hotel_id   BIGINT NOT NULL,
    token_date DATE   NOT NULL,
    last_token INT    NOT NULL DEFAULT 0,
    PRIMARY KEY (hotel_id, token_date),
    CONSTRAINT fk_counters_hotel FOREIGN KEY (hotel_id) REFERENCES hotels (id) ON DELETE CASCADE
);

CREATE TABLE pickup_tokens (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id     BIGINT      NOT NULL,
    hotel_id     BIGINT      NOT NULL,
    token_date   DATE        NOT NULL,
    token_number INT         NOT NULL,
    created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uq_tokens_order UNIQUE (order_id),
    CONSTRAINT uq_tokens_hotel_day_number UNIQUE (hotel_id, token_date, token_number),
    CONSTRAINT fk_tokens_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_tokens_hotel FOREIGN KEY (hotel_id) REFERENCES hotels (id)
);
