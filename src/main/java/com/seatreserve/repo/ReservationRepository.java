package com.seatreserve.repo;

import com.seatreserve.model.Reservation;
import com.seatreserve.model.ReservationStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class ReservationRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ReservationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts a new held reservation. The unique key (user_id, idempotency_key) means a concurrent
     * retry with the same key collides here and throws DuplicateKeyException - the caller then
     * returns the original reservation (idempotent replay).
     */
    public void insertHeld(String id, String showId, String userId, long amountPaise,
                           String seatsCsv, String idempotencyKey, String fingerprint, long ttlSeconds) {
        jdbc.update("""
                        INSERT INTO reservations
                            (id, show_id, user_id, status, amount_paise, seats_csv,
                             idempotency_key, request_fingerprint, expires_at)
                        VALUES
                            (:id, :showId, :userId, 'held', :amount, :seatsCsv,
                             :key, :fp, NOW(6) + INTERVAL :ttl SECOND)
                        """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("showId", showId)
                        .addValue("userId", userId)
                        .addValue("amount", amountPaise)
                        .addValue("seatsCsv", seatsCsv)
                        .addValue("key", idempotencyKey)
                        .addValue("fp", fingerprint)
                        .addValue("ttl", ttlSeconds));
    }

    public Optional<Reservation> findByUserAndKey(String userId, String idempotencyKey) {
        return one("SELECT * FROM reservations WHERE user_id = :userId AND idempotency_key = :key",
                new MapSqlParameterSource().addValue("userId", userId).addValue("key", idempotencyKey));
    }

    public Optional<Reservation> findById(String id) {
        return one("SELECT * FROM reservations WHERE id = :id",
                new MapSqlParameterSource("id", id));
    }

    /** Locks the reservation row so concurrent confirm/cancel on the same reservation serialize. */
    public Optional<Reservation> findByIdForUpdate(String id) {
        return one("SELECT * FROM reservations WHERE id = :id FOR UPDATE",
                new MapSqlParameterSource("id", id));
    }

    public void updateStatus(String id, ReservationStatus status) {
        jdbc.update("UPDATE reservations SET status = :status WHERE id = :id",
                new MapSqlParameterSource().addValue("status", status.name()).addValue("id", id));
    }

    /** Sweeper step: mark held reservations whose hold window elapsed as expired. */
    public int expireHeldPastDue() {
        return jdbc.update(
                "UPDATE reservations SET status = 'expired' WHERE status = 'held' AND expires_at < NOW(6)",
                new MapSqlParameterSource());
    }

    private Optional<Reservation> one(String sql, MapSqlParameterSource params) {
        List<Reservation> rows = jdbc.query(sql, params, (rs, n) -> new Reservation(
                rs.getString("id"),
                rs.getString("show_id"),
                rs.getString("user_id"),
                ReservationStatus.valueOf(rs.getString("status")),
                rs.getLong("amount_paise"),
                rs.getString("seats_csv"),
                rs.getString("idempotency_key"),
                rs.getString("request_fingerprint"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("expires_at"))));
        return rows.stream().findFirst();
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
