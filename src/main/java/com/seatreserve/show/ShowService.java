package com.seatreserve.show;

import com.seatreserve.config.AppProperties;
import com.seatreserve.model.SeatView;
import com.seatreserve.model.Show;
import com.seatreserve.repo.SeatRepository;
import com.seatreserve.repo.ShowRepository;
import com.seatreserve.web.BadRequestException;
import com.seatreserve.web.NotFoundException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ShowService {

    private final ShowRepository showRepo;
    private final SeatRepository seatRepo;
    private final AppProperties props;
    private final MeterRegistry meterRegistry;
    private final Set<String> gaugeRegistered = ConcurrentHashMap.newKeySet();

    public ShowService(ShowRepository showRepo, SeatRepository seatRepo, AppProperties props,
                       MeterRegistry meterRegistry) {
        this.showRepo = showRepo;
        this.seatRepo = seatRepo;
        this.props = props;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public ShowResponse create(CreateShowRequest req) {
        Set<String> unique = new HashSet<>(req.seats());
        if (unique.size() != req.seats().size()) {
            throw new BadRequestException("seats contains duplicates");
        }
        int limit = req.per_user_limit() != null ? req.per_user_limit() : props.getDefaultPerUserLimit();
        if (limit < 1) {
            throw new BadRequestException("per_user_limit must be >= 1");
        }
        String id = UUID.randomUUID().toString();
        showRepo.insert(new Show(id, req.name(), req.price_paise(), limit, null));
        seatRepo.insertAll(id, req.seats());
        registerSeatsAvailableGauge(id);
        return get(id);
    }

    public ShowResponse get(String id) {
        Show show = showRepo.findById(id).orElseThrow(() -> new NotFoundException("show not found: " + id));
        long available = seatRepo.countByStatus(id, "available");
        long held = seatRepo.countByStatus(id, "held");
        long confirmed = seatRepo.countByStatus(id, "confirmed");
        long total = seatRepo.countTotal(id);
        ShowResponse.Counts counts = new ShowResponse.Counts(available, held, confirmed);
        List<SeatView> seats = seatRepo.listSeats(id);
        return new ShowResponse(
                show.id(), show.name(), show.pricePaise(), show.perUserLimit(),
                total, counts, counts.sum() == total, seats);
    }

    /** Registers the seats_available{show_id} gauge once per show; value is read live from the DB. */
    private void registerSeatsAvailableGauge(String showId) {
        if (gaugeRegistered.add(showId)) {
            Gauge.builder("seats_available", showId, seatRepo::countAvailable)
                    .description("Seats currently available for the show")
                    .tag("show_id", showId)
                    .register(meterRegistry);
        }
    }
}
