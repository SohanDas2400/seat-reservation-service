package com.seatreserve.repo;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Per-(show,user) seat counter. The limit check runs under a row lock taken with
 * SELECT ... FOR UPDATE, which serializes a single user's concurrent reserves for a show so the
 * per-user limit can never be exceeded, without blocking other users.
 */
@Repository
public class CounterRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public CounterRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Ensures the counter row exists, then locks it and returns the current held+confirmed count. */
    public int lockAndGet(String showId, String userId) {
        jdbc.update("INSERT IGNORE INTO show_user_counter (show_id, user_id, held_count) VALUES (:sid, :uid, 0)",
                new MapSqlParameterSource().addValue("sid", showId).addValue("uid", userId));
        Integer c = jdbc.queryForObject(
                "SELECT held_count FROM show_user_counter WHERE show_id = :sid AND user_id = :uid FOR UPDATE",
                new MapSqlParameterSource().addValue("sid", showId).addValue("uid", userId),
                Integer.class);
        return c == null ? 0 : c;
    }

    public void add(String showId, String userId, int delta) {
        jdbc.update("""
                        UPDATE show_user_counter
                           SET held_count = GREATEST(held_count + :delta, 0)
                         WHERE show_id = :sid AND user_id = :uid
                        """,
                new MapSqlParameterSource().addValue("delta", delta).addValue("sid", showId).addValue("uid", userId));
    }

    /**
     * Sweeper self-heal: recompute every counter straight from the authoritative seats table.
     * This erases any drift left by rare confirm/expiry races.
     */
    public int recomputeAll() {
        return jdbc.update("""
                        UPDATE show_user_counter c
                           SET held_count = (
                               SELECT COUNT(*) FROM seats s
                                WHERE s.show_id = c.show_id
                                  AND s.held_by = c.user_id
                                  AND s.status IN ('held', 'confirmed'))
                        """,
                new MapSqlParameterSource());
    }
}
