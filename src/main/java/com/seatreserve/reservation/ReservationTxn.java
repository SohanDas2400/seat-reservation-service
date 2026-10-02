package com.seatreserve.reservation;

import com.seatreserve.config.AppProperties;
import com.seatreserve.model.Reservation;
import com.seatreserve.model.ReservationStatus;
import com.seatreserve.model.Show;
import com.seatreserve.repo.CounterRepository;
import com.seatreserve.repo.ReservationRepository;
import com.seatreserve.repo.SeatRepository;
import com.seatreserve.web.ConflictException;
import com.seatreserve.web.ForbiddenException;
import com.seatreserve.web.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The transactional heart of the service. Every method runs in a single READ COMMITTED transaction;
 * any thrown exception rolls the whole thing back (all-or-nothing).
 *
 * Lock order within reserve is: idempotency index -> seat rows (sorted) -> counter row. The same
 * "seats before counter" order is used by the sweeper, and sorting the seats gives every multi-seat
 * request the same global row-lock order, so overlapping requests cannot deadlock each other.
 */
@Service
public class ReservationTxn {

    private final SeatRepository seatRepo;
    private final ReservationRepository reservationRepo;
    private final CounterRepository counterRepo;
    private final AppProperties props;

    public ReservationTxn(SeatRepository seatRepo, ReservationRepository reservationRepo,
                          CounterRepository counterRepo, AppProperties props) {
        this.seatRepo = seatRepo;
        this.reservationRepo = reservationRepo;
        this.counterRepo = counterRepo;
        this.props = props;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Reservation reserve(String userId, Show show, List<String> sortedSeats,
                               String key, String fingerprint) {
        long ttl = props.getHoldTtlSeconds();
        String rid = UUID.randomUUID().toString();
        long amount = show.pricePaise() * (long) sortedSeats.size();
        String seatsCsv = String.join(",", sortedSeats);

        // 1. Idempotency: a concurrent retry with the same key collides on uq_idem here.
        reservationRepo.insertHeld(rid, show.id(), userId, amount, seatsCsv, key, fingerprint, ttl);

        // 2. Claim each seat atomically, in sorted order. Any loss => clean 409, whole txn rolls back.
        for (String seat : sortedSeats) {
            int won = seatRepo.claim(show.id(), seat, userId, rid, ttl);
            if (won == 0) {
                throw new ConflictException(ConflictException.SEAT_TAKEN, "seat not available: " + seat);
            }
        }

        // 3. Per-user limit, under a row lock that serializes this user's reserves for this show.
        int current = counterRepo.lockAndGet(show.id(), userId);
        if (current + sortedSeats.size() > show.perUserLimit()) {
            throw new ConflictException(ConflictException.PER_USER_LIMIT,
                    "per-user limit of " + show.perUserLimit() + " reached for this show");
        }
        counterRepo.add(show.id(), userId, sortedSeats.size());

        Instant expiresAt = Instant.now().plusSeconds(ttl);
        return new Reservation(rid, show.id(), userId, ReservationStatus.held, amount, seatsCsv,
                key, fingerprint, Instant.now(), expiresAt);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Reservation confirm(String userId, String reservationId) {
        Reservation r = reservationRepo.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new NotFoundException("reservation not found: " + reservationId));
        if (!r.userId().equals(userId)) {
            throw new ForbiddenException("not your reservation");
        }
        if (r.status() == ReservationStatus.confirmed) {
            return r; // idempotent confirm
        }
        if (r.status() != ReservationStatus.held) {
            throw new ConflictException(ConflictException.HOLD_EXPIRED, "hold is no longer active");
        }
        int confirmed = seatRepo.confirmByReservation(reservationId);
        if (confirmed < r.seats().size()) {
            // part of the hold already expired/was swept -> fail the whole confirm
            throw new ConflictException(ConflictException.HOLD_EXPIRED, "hold expired before confirmation");
        }
        reservationRepo.updateStatus(reservationId, ReservationStatus.confirmed);
        return withStatus(r, ReservationStatus.confirmed);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Reservation cancel(String userId, String reservationId) {
        Reservation r = reservationRepo.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new NotFoundException("reservation not found: " + reservationId));
        if (!r.userId().equals(userId)) {
            throw new ForbiddenException("not your reservation");
        }
        if (r.status() == ReservationStatus.cancelled || r.status() == ReservationStatus.expired) {
            return r; // idempotent cancel
        }
        int released = seatRepo.releaseByReservation(reservationId);
        reservationRepo.updateStatus(reservationId, ReservationStatus.cancelled);
        if (released > 0) {
            counterRepo.add(r.showId(), r.userId(), -released);
        }
        return withStatus(r, ReservationStatus.cancelled);
    }

    private static Reservation withStatus(Reservation r, ReservationStatus status) {
        return new Reservation(r.id(), r.showId(), r.userId(), status, r.amountPaise(), r.seatsCsv(),
                r.idempotencyKey(), r.requestFingerprint(), r.createdAt(), r.expiresAt());
    }
}
