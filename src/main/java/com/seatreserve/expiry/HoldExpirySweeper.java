package com.seatreserve.expiry;

import com.seatreserve.repo.CounterRepository;
import com.seatreserve.repo.ReservationRepository;
import com.seatreserve.repo.SeatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Time-boxed holds auto-expire. Every tick (within one transaction):
 *   1. return expired held seats to available (confirmed seats are never touched),
 *   2. mark the matching reservations expired,
 *   3. recompute per-user counters straight from the seats table (self-heals any drift).
 * Failures are swallowed and retried next tick, so a transient lock contention never crashes anything.
 */
@Component
public class HoldExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(HoldExpirySweeper.class);

    private final SeatRepository seatRepo;
    private final ReservationRepository reservationRepo;
    private final CounterRepository counterRepo;
    private final TransactionTemplate txTemplate;

    public HoldExpirySweeper(SeatRepository seatRepo, ReservationRepository reservationRepo,
                             CounterRepository counterRepo, PlatformTransactionManager txManager) {
        this.seatRepo = seatRepo;
        this.reservationRepo = reservationRepo;
        this.counterRepo = counterRepo;
        this.txTemplate = new TransactionTemplate(txManager);
        this.txTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Scheduled(fixedDelayString = "${app.sweep-interval-ms:5000}", initialDelay = 5000)
    public void sweep() {
        try {
            Integer released = txTemplate.execute(status -> {
                int n = seatRepo.releaseExpiredHolds();
                reservationRepo.expireHeldPastDue();
                counterRepo.recomputeAll();
                return n;
            });
            if (released != null && released > 0) {
                log.info("sweeper released {} expired held seat(s)", released);
            }
        } catch (Exception e) {
            log.warn("sweeper tick failed, will retry next interval: {}", e.getMessage());
        }
    }
}
