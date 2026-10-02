package com.seatreserve;

import com.seatreserve.reservation.ReservationService;
import com.seatreserve.repo.SeatRepository;
import com.seatreserve.show.CreateShowRequest;
import com.seatreserve.show.ShowResponse;
import com.seatreserve.web.ConflictException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the correctness bar against a real MySQL (Testcontainers). Skipped automatically when
 * Docker is not available, so `mvn package` stays green on machines without Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class ReservationConcurrencyTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withCommand("--innodb-lock-wait-timeout=10");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    ReservationService reservationService;
    @Autowired
    com.seatreserve.show.ShowService showService;
    @Autowired
    SeatRepository seatRepo;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available - skipping concurrency test");
    }

    @Test
    void hotSeat_exactlyOneWinner_everyoneElse409_andReconciles() throws Exception {
        ShowResponse show = newShow("hot", 50, 4);
        int contenders = 200;

        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger seatTaken = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(64);
        CountDownLatch done = new CountDownLatch(contenders);
        for (int i = 0; i < contenders; i++) {
            final int n = i;
            pool.submit(() -> {
                try {
                    start.await();
                    var r = reservationService.reserve("user-" + n, show.id(), List.of("A1"), "key-" + n);
                    if (r.created()) created.incrementAndGet();
                } catch (ConflictException ce) {
                    if (ConflictException.SEAT_TAKEN.equals(ce.getCode())) seatTaken.incrementAndGet();
                    else other.incrementAndGet();
                } catch (Exception e) {
                    other.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await();
        pool.shutdown();

        assertThat(created.get()).as("exactly one winner for the hot seat").isEqualTo(1);
        assertThat(seatTaken.get()).as("everyone else gets a clean seat_taken").isEqualTo(contenders - 1);
        assertThat(other.get()).as("no unexpected errors (no 5xx-equivalents)").isZero();
        assertReconciled(show.id());
    }

    @Test
    void perUserLimit_neverExceeded_underParallelReserves() throws Exception {
        ShowResponse show = newShow("limit", 50, 4);
        int attempts = 10;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger created = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch done = new CountDownLatch(attempts);
        for (int i = 0; i < attempts; i++) {
            final int n = i;
            pool.submit(() -> {
                try {
                    start.await();
                    var r = reservationService.reserve("solo", show.id(), List.of("A" + (n + 1)), "k-" + n);
                    if (r.created()) created.incrementAndGet();
                } catch (ConflictException ignored) {
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await();
        pool.shutdown();

        assertThat(created.get()).as("at most the per-user limit succeed").isLessThanOrEqualTo(4);
        assertThat(seatRepo.countByStatus(show.id(), "held")).isLessThanOrEqualTo(4);
        assertReconciled(show.id());
    }

    @Test
    void idempotency_sameKeyReservesExactlyOnce() throws Exception {
        ShowResponse show = newShow("idem", 50, 4);
        int fires = 25;
        String key = "same-key";
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger replays = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(25);
        CountDownLatch done = new CountDownLatch(fires);
        for (int i = 0; i < fires; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    var r = reservationService.reserve("buyer", show.id(), List.of("A5"), key);
                    if (r.created()) created.incrementAndGet();
                    else replays.incrementAndGet();
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await();
        pool.shutdown();

        assertThat(created.get()).as("same key creates exactly one reservation").isEqualTo(1);
        assertThat(replays.get()).as("all other retries are idempotent replays").isEqualTo(fires - 1);
        assertThat(seatRepo.countByStatus(show.id(), "held")).isEqualTo(1);
        assertReconciled(show.id());
    }

    private ShowResponse newShow(String name, int seats, int limit) {
        List<String> seatNos = IntStream.rangeClosed(1, seats).mapToObj(i -> "A" + i).toList();
        return showService.create(new CreateShowRequest(name + "-" + System.nanoTime(), seatNos, 25000, limit));
    }

    private void assertReconciled(String showId) {
        long available = seatRepo.countByStatus(showId, "available");
        long held = seatRepo.countByStatus(showId, "held");
        long confirmed = seatRepo.countByStatus(showId, "confirmed");
        long total = seatRepo.countTotal(showId);
        assertThat(available + held + confirmed).as("reconciliation invariant").isEqualTo(total);
    }
}
