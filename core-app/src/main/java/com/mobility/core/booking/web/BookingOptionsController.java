package com.mobility.core.booking.web;

import com.mobility.core.booking.BookingPolicy;
import com.mobility.core.booking.BookingType;
import com.mobility.core.pricing.FareModel;

import io.swagger.v3.oas.annotations.Operation;

import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/v1/booking/options")
class BookingOptionsController {
    private final BookingPolicy policy;

    BookingOptionsController(BookingPolicy policy) {
        this.policy = policy;
    }

    record Mode(BookingType type, FareModel fareModel) {}

    record Options(
            List<BookingType> modes,
            List<Mode> combinations,
            long maximumScheduleDays,
            int minimumScheduleNoticeSeconds,
            String timezone) {}

    @GetMapping
    @Operation(
            summary = "Enabled personal booking type and fare-model combinations",
            description =
                    "CASH only. combinations is authoritative for fare availability; modes retains"
                        + " legacy quoted-type discovery. Immediate and metered rollout flags"
                        + " default off. No production meter or automatic matching guarantee.")
    Options options() {
        var combinations = policy.personalCombinations().stream()
                .map(c -> new Mode(c.type(), c.fareModel())).toList();
        var modes =
                combinations.stream()
                        .filter(m -> m.fareModel() == FareModel.QUOTED)
                        .map(Mode::type)
                        .toList();
        return new Options(
                modes,
                combinations,
                policy.maximumScheduleDays(),
                policy.minimumScheduleNoticeSeconds(),
                "Asia/Phnom_Penh");
    }
}
