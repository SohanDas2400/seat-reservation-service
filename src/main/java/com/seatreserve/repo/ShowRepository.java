package com.seatreserve.repo;

import com.seatreserve.model.Show;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
public class ShowRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ShowRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Show show) {
        jdbc.update("""
                        INSERT INTO shows (id, name, price_paise, per_user_limit)
                        VALUES (:id, :name, :price, :limit)
                        """,
                new MapSqlParameterSource()
                        .addValue("id", show.id())
                        .addValue("name", show.name())
                        .addValue("price", show.pricePaise())
                        .addValue("limit", show.perUserLimit()));
    }

    public Optional<Show> findById(String id) {
        List<Show> rows = jdbc.query(
                "SELECT id, name, price_paise, per_user_limit, created_at FROM shows WHERE id = :id",
                new MapSqlParameterSource("id", id),
                (rs, n) -> new Show(
                        rs.getString("id"),
                        rs.getString("name"),
                        rs.getLong("price_paise"),
                        rs.getInt("per_user_limit"),
                        toInstant(rs.getTimestamp("created_at"))));
        return rows.stream().findFirst();
    }

    private static java.time.Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
