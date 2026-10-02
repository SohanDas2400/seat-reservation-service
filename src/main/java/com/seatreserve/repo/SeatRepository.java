package com.seatreserve.repo;

import com.seatreserve.model.SeatView;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Repository
public class SeatRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public SeatRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertAll(String showId, List<String> seatNos) {
        List<SqlParameterSource> batch = new ArrayList<>(seatNos.size());
        for (String seatNo : seatNos) {
            batch.add(new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID().toString())
                    .addValue("showId", showId)
                    .addValue("seatNo", seatNo));
        }
        jdbc.batchUpdate(
                "INSERT INTO seats (id, show_id, seat_no, status) VALUES (:id, :showId, :seatNo, 'available')",
                batch.toArray(SqlParameterSource[]::new));
    }

    /** Returns which of the requested seat numbers actually exist for the show. */
    public Set<String> existingSeatNos(String showId, Collection<String> seatNos) {
        List<String> found = jdbc.query(
                "SELECT seat_no FROM seats WHERE show_id = :showId AND seat_no IN (:seats)",
                new MapSqlParameterSource().addValue("showId", showId).addValue("seats", seatNos),
                (rs, n) -> rs.getString("seat_no"));
        return new HashSet<>(found);
    }

    /**
     * THE atomic decision. Transitions a single seat available -> held, guarded on the current
     * state. Returns 1 if this caller won the seat, 0 if it was already held/confirmed (a clean
     * decline). Only one transaction can ever move a given row out of 'available'.
     */
    public int claim(String showId, String seatNo, String userId, String reservationId, long ttlSeconds) {
        return jdbc.update("""
                        UPDATE seats
                           SET status = 'held',
                               held_by = :uid,
                               reservation_id = :rid,
                               hold_expires_at = NOW(6) + INTERVAL :ttl SECOND
                         WHERE show_id = :showId
                           AND seat_no = :seatNo
                           AND status = 'available'
                        """,
                new MapSqlParameterSource()
                        .addValue("uid", userId)
                        .addValue("rid", reservationId)
                        .addValue("ttl", ttlSeconds)
                        .addValue("showId", showId)
                        .addValue("seatNo", seatNo));
    }

    /** Confirms all still-held seats of a reservation. Returns how many were confirmed. */
    public int confirmByReservation(String reservationId) {
        return jdbc.update(
                "UPDATE seats SET status = 'confirmed', hold_expires_at = NULL " +
                        "WHERE reservation_id = :rid AND status = 'held'",
                new MapSqlParameterSource("rid", reservationId));
    }

    /**
     * Releases every seat currently held/confirmed under this reservation back to available.
     * Scoped to this reservation only, so it can never resurrect a seat confirmed to someone else.
     */
    public int releaseByReservation(String reservationId) {
        return jdbc.update("""
                        UPDATE seats
                           SET status = 'available', held_by = NULL, reservation_id = NULL, hold_expires_at = NULL
                         WHERE reservation_id = :rid
                           AND status IN ('held', 'confirmed')
                        """,
                new MapSqlParameterSource("rid", reservationId));
    }

    /** Sweeper step: return expired holds to available. Confirmed seats are untouched. */
    public int releaseExpiredHolds() {
        return jdbc.update("""
                        UPDATE seats
                           SET status = 'available', held_by = NULL, reservation_id = NULL, hold_expires_at = NULL
                         WHERE status = 'held'
                           AND hold_expires_at < NOW(6)
                        """,
                new MapSqlParameterSource());
    }

    public long countByStatus(String showId, String status) {
        Long c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM seats WHERE show_id = :showId AND status = :status",
                new MapSqlParameterSource().addValue("showId", showId).addValue("status", status),
                Long.class);
        return c == null ? 0L : c;
    }

    public long countAvailable(String showId) {
        return countByStatus(showId, "available");
    }

    public long countTotal(String showId) {
        Long c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM seats WHERE show_id = :showId",
                new MapSqlParameterSource("showId", showId), Long.class);
        return c == null ? 0L : c;
    }

    public List<SeatView> listSeats(String showId) {
        return jdbc.query(
                "SELECT seat_no, status FROM seats WHERE show_id = :showId ORDER BY LENGTH(seat_no), seat_no",
                new MapSqlParameterSource("showId", showId),
                (rs, n) -> new SeatView(rs.getString("seat_no"), rs.getString("status")));
    }
}
