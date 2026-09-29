package io.muleshield.core.time;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;

import io.muleshield.core.policy.PolicyPack;

/**
 * Business days in a jurisdiction: weekdays that aren't public holidays. The UK lets a bank delay a
 * suspicious push payment "until the end of the fourth business day following the time of receipt
 * of the payment order", and that deadline is counted here.                         [UK-PSREGS-2024]
 */
public final class BusinessCalendar {

    private final ZoneId zone;
    private final Set<LocalDate> holidays;

    public BusinessCalendar(ZoneId zone, Set<LocalDate> holidays) {
        this.zone = zone;
        this.holidays = Set.copyOf(holidays);
    }

    public static BusinessCalendar of(PolicyPack pack) {
        return new BusinessCalendar(pack.zone(), Set.copyOf(pack.holidays()));
    }

    public boolean isBusinessDay(LocalDate day) {
        DayOfWeek dow = day.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY && !holidays.contains(day);
    }

    /**
     * The end of the {@code n}th business day after the local day of {@code receivedAt}, returned as
     * the first instant after it (midnight local time), so "hold until" is an exclusive bound.
     */
    public Instant endOfNthBusinessDayAfter(Instant receivedAt, int n) {
        LocalDate day = receivedAt.atZone(zone).toLocalDate();
        int counted = 0;
        while (counted < n) {
            day = day.plusDays(1);
            if (isBusinessDay(day)) {
                counted++;
            }
        }
        return day.plusDays(1).atStartOfDay(zone).toInstant();
    }
}
