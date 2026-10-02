package com.seatreserve.reservation;

import com.seatreserve.metrics.ReservationMetrics;
import com.seatreserve.model.Reservation;
import com.seatreserve.model.Show;
import com.seatreserve.repo.ReservationRepository;
import com.seatreserve.repo.SeatRepository;
import com.seatreserve.repo.ShowRepository;
import com.seatreserve.util.Retries;
import com.seatreserve.web.BadRequestException;
import com.seatreserve.web.ConflictException;
import com.seatreserve.web.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Orchestrates reservation use-cases around the transactional core: validates input, enforces
 * idempotency (including the concurrent-same-key race), records metrics, and retries transient
 * lock failures so a contended request never leaks a 5xx.
 */
@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);
    private static final int MAX_ATTEMPTS = 8;

    private final ShowRepository showRepo;
    private final SeatRepository seatRepo;
    private final ReservationRepository reservationRepo;
    private final ReservationTxn txn;
    private final ReservationMetrics metrics;

    public ReservationService(ShowRepository showRepo, SeatRepository seatRepo,
                              ReservationRepository reservationRepo, ReservationTxn txn,
                              ReservationMetrics metrics) {
        this.showRepo = showRepo;
        this.seatRepo = seatRepo;
        this.reservationRepo = reservationRepo;
        this.txn = txn;
        this.metrics = metrics;
    }

    /** created=true -> 201 (new reservation); created=false -> 200 (idempotent replay). */
    public record ReserveResult(ReservationResponse body, boolean created) {
    }

    public ReserveResult reserve(String userId, String showId, List<String> requestedSeats, String key) {
        if (key == null || key.isBlank()) {
            throw new BadRequestException("idempotency_key is required (Idempotency-Key header or body field)");
        }
        if (requestedSeats == null || requestedSeats.isEmpty()) {
            throw new BadRequestException("seats is required");
        }
        List<String> seats = requestedSeats.stream().map(String::trim).distinct().sorted().toList();
        if (seats.size() != requestedSeats.size()) {
            throw new BadRequestException("seats contains duplicates");
        }
        Show show = showRepo.findById(showId)
                .orElseThrow(() -> new NotFoundException("show not found: " + showId));

        Set<String> existing = seatRepo.existingSeatNos(showId, seats);
        for (String s : seats) {
            if (!existing.contains(s)) {
                throw new NotFoundException("seat not found in show: " + s);
            }
        }
        String fingerprint = fingerprint(showId, seats);

        try {
            ReserveResult result = Retries.withRetry(MAX_ATTEMPTS,
                    () -> reserveOnce(userId, show, seats, key, fingerprint));
            log.info("reserve {} show={} user={} seats={}",
                    result.created() ? "secured" : "idempotent-replay", showId, userId, seats);
            return result;
        } catch (ConflictException ce) {
            metrics.declined(ce.getCode());
            log.info("reserve declined reason={} show={} user={} seats={}", ce.getCode(), showId, userId, seats);
            throw ce;
        } catch (TransientDataAccessException te) {
            // Retries exhausted under extreme contention: a clean 409, never a 5xx. No seat was sold.
            metrics.declined("transient_conflict");
            log.info("reserve declined reason=transient_conflict show={} user={} seats={}", showId, userId, seats);
            throw new ConflictException("transient_conflict", "could not secure seats, please retry");
        }
    }

    private ReserveResult reserveOnce(String userId, Show show, List<String> seats,
                                      String key, String fingerprint) {
        // Fast path for ordinary retries: the key already produced a reservation.
        Optional<Reservation> existing = reservationRepo.findByUserAndKey(userId, key);
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint);
        }
        try {
            Reservation r = txn.reserve(userId, show, seats, key, fingerprint);
            metrics.confirmed();
            return new ReserveResult(toResponse(r), true);
        } catch (DuplicateKeyException dup) {
            // A concurrent request with the same key won the insert; return its reservation.
            Reservation r = reservationRepo.findByUserAndKey(userId, key).orElseThrow(() -> dup);
            return replay(r, fingerprint);
        }
    }

    private ReserveResult replay(Reservation r, String fingerprint) {
        if (!r.requestFingerprint().equals(fingerprint)) {
            throw new ConflictException(ConflictException.IDEMPOTENCY_CONFLICT,
                    "idempotency key already used with a different set of seats");
        }
        metrics.declined("idempotent_replay");
        return new ReserveResult(toResponse(r), false);
    }

    public ReservationResponse confirm(String userId, String reservationId) {
        try {
            return toResponse(Retries.withRetry(MAX_ATTEMPTS, () -> txn.confirm(userId, reservationId)));
        } catch (TransientDataAccessException te) {
            throw new ConflictException("transient_conflict", "please retry");
        }
    }

    public ReservationResponse cancel(String userId, String reservationId) {
        try {
            return toResponse(Retries.withRetry(MAX_ATTEMPTS, () -> txn.cancel(userId, reservationId)));
        } catch (TransientDataAccessException te) {
            throw new ConflictException("transient_conflict", "please retry");
        }
    }

    private static ReservationResponse toResponse(Reservation r) {
        return new ReservationResponse(r.id(), r.showId(), r.userId(), r.seats(),
                r.amountPaise(), r.status().name(), r.expiresAt());
    }

    private static String fingerprint(String showId, List<String> sortedSeats) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((showId + "|" + String.join(",", sortedSeats)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
