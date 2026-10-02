-- Seat Reservation schema (MySQL 8 / InnoDB).
-- All money is integer paise (BIGINT). UUIDs are CHAR(36); external user ids are VARCHAR(64).

CREATE TABLE shows (
    id             CHAR(36)     NOT NULL PRIMARY KEY,
    name           VARCHAR(190) NOT NULL,
    price_paise    BIGINT       NOT NULL,
    per_user_limit INT          NOT NULL DEFAULT 4,
    created_at     TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uq_show_name UNIQUE (name)
) ENGINE = InnoDB;

CREATE TABLE seats (
    id              CHAR(36)    NOT NULL PRIMARY KEY,
    show_id         CHAR(36)    NOT NULL,
    seat_no         VARCHAR(32) NOT NULL,
    status          ENUM('available','held','confirmed') NOT NULL DEFAULT 'available',
    held_by         VARCHAR(64) NULL,
    reservation_id  CHAR(36)    NULL,
    hold_expires_at TIMESTAMP(6) NULL,
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_seats_show FOREIGN KEY (show_id) REFERENCES shows (id),
    -- A seat number is unique within a show: makes a double-row for the same seat impossible.
    CONSTRAINT uq_seat UNIQUE (show_id, seat_no),
    KEY ix_seat_status (show_id, status),
    KEY ix_seat_expiry (status, hold_expires_at)
) ENGINE = InnoDB;

CREATE TABLE reservations (
    id                  CHAR(36)     NOT NULL PRIMARY KEY,
    show_id             CHAR(36)     NOT NULL,
    user_id             VARCHAR(64)  NOT NULL,
    status              ENUM('held','confirmed','cancelled','expired') NOT NULL,
    amount_paise        BIGINT       NOT NULL,
    seats_csv           VARCHAR(2000) NOT NULL,
    idempotency_key     VARCHAR(190) NOT NULL,
    request_fingerprint CHAR(64)     NOT NULL,
    created_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at          TIMESTAMP(6) NULL,
    CONSTRAINT fk_resv_show FOREIGN KEY (show_id) REFERENCES shows (id),
    -- Exactly-once per (user, idempotency_key). A retry collides here.
    CONSTRAINT uq_idem UNIQUE (user_id, idempotency_key),
    KEY ix_resv_user_show (show_id, user_id)
) ENGINE = InnoDB;

-- One row per (show, user); row-locked with SELECT ... FOR UPDATE to serialize a user's
-- concurrent reserves so the per-user limit cannot be exceeded under load.
CREATE TABLE show_user_counter (
    show_id    CHAR(36)    NOT NULL,
    user_id    VARCHAR(64) NOT NULL,
    held_count INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (show_id, user_id)
) ENGINE = InnoDB;
