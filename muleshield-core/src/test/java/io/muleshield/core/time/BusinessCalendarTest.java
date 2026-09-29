package io.muleshield.core.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import io.muleshield.core.policy.PolicyPacks;

class BusinessCalendarTest {

    private final BusinessCalendar uk = BusinessCalendar.of(PolicyPacks.load("UK-PSR-2024"));
    private final ZoneId london = ZoneId.of("Europe/London");

    @Test
    void fourthBusinessDaySkipsTheWeekend() {
        // Received Thursday 1 Oct 2026: Fri 2, Mon 5, Tue 6, Wed 7 -> release at midnight starting Thu 8.
        Instant received = ZonedDateTime.of(2026, 10, 1, 15, 0, 0, 0, london).toInstant();
        assertThat(uk.endOfNthBusinessDayAfter(received, 4))
                .isEqualTo(ZonedDateTime.of(2026, 10, 8, 0, 0, 0, 0, london).toInstant());
    }

    @Test
    void fourthBusinessDaySkipsBankHolidays() {
        // Received Wed 23 Dec 2026: Thu 24, (Fri 25 holiday, weekend, Mon 28 holiday) Tue 29, Wed 30, Thu 31.
        Instant received = ZonedDateTime.of(2026, 12, 23, 10, 0, 0, 0, london).toInstant();
        assertThat(uk.endOfNthBusinessDayAfter(received, 4))
                .isEqualTo(ZonedDateTime.of(2027, 1, 1, 0, 0, 0, 0, london).toInstant());
    }
}
