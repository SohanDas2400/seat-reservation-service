package com.seatreserve.show;

import com.seatreserve.model.SeatView;

import java.util.List;

public record ShowResponse(
        String id,
        String name,
        long price_paise,
        int per_user_limit,
        long total_seats,
        Counts counts,
        boolean reconciled,
        List<SeatView> seats
) {
    public record Counts(long available, long held, long confirmed) {
        public long sum() {
            return available + held + confirmed;
        }
    }
}
