package io.muleshield.sim;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** What a run measured, from the bank's point of view: victims' money saved, customers annoyed, mules caught. */
final class Metrics {

    enum Outcome { EXECUTED, DECLINED, CANCELLED_IN_HOLD, STOPPED_BY_PASSKEY, STOPPED_PAYEE_HELD }

    static final class Scam {
        int cases;
        int attempts;
        double atRisk;
        double executed;
        double stoppedValue;
        final EnumMap<Outcome, Integer> outcomes = new EnumMap<>(Outcome.class);
    }

    private final EnumMap<Typology, Scam> scams = new EnumMap<>(Typology.class);
    private final double scale;

    int legitPayments;
    double legitValue;
    int legitStepUps;
    int legitHolds;
    int legitDeclines;
    double legitValueDelayed;
    double legitHoldHours;
    private final Set<String> legitAffected = new HashSet<>();
    private final Set<String> legitPayers = new HashSet<>();

    /** Mule accounts at the measuring bank: when money first reached them, and when they were flagged and held. */
    final Map<String, Instant> muleFirstCredit = new HashMap<>();
    final Map<String, Instant> muleFlagged = new HashMap<>();
    final Map<String, Instant> muleHeld = new HashMap<>();
    double muleInflow;
    double frozenByEngine;
    double frozenByPolice;
    int legitFlagged;
    int legitWronglyHeld;
    double legitWrongHoldDays;
    int legitAccountsMonitored;

    private long[] latencies = new long[1 << 16];
    private int latencyCount;
    private long latencySeen;
    private final Rng reservoir = new Rng(99);

    Metrics(double scale) {
        this.scale = scale;
    }

    Scam scam(Typology t) {
        return scams.computeIfAbsent(t, k -> new Scam());
    }

    void scamCase(Typology t) {
        scam(t).cases++;
    }

    void scamAttempt(Typology t, double amount, boolean firstAttempt) {
        Scam s = scam(t);
        s.attempts++;
        if (firstAttempt) {
            s.atRisk += amount;
        }
    }

    void scamOutcome(Typology t, Outcome o, double amount) {
        Scam s = scam(t);
        s.outcomes.merge(o, 1, Integer::sum);
        if (o == Outcome.EXECUTED) {
            s.executed += amount;
        } else {
            s.stoppedValue += amount;
        }
    }

    void legit(String payer, double amount) {
        legitPayments++;
        legitValue += amount;
        legitPayers.add(payer);
    }

    void legitFriction(String payer, boolean stepUp, boolean hold, boolean decline, double amount, double holdHours) {
        if (stepUp) {
            legitStepUps++;
        }
        if (hold) {
            legitHolds++;
            legitValueDelayed += amount;
            legitHoldHours += holdHours;
        }
        if (decline) {
            legitDeclines++;
        }
        legitAffected.add(payer);
    }

    void latency(long nanos) {
        latencySeen++;
        if (latencyCount < latencies.length) {
            latencies[latencyCount++] = nanos;
        } else {
            long j = (long) (reservoir.u() * latencySeen);
            if (j < latencies.length) {
                latencies[(int) j] = nanos;
            }
        }
    }

    Map<String, Object> summary(SimConfig config) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("strategy", config.strategy().name());
        out.put("strategyLabel", config.strategy().label());
        out.put("pack", config.packId());
        out.put("seed", config.seed());
        out.put("customers", config.customers());
        out.put("measuredDays", config.days() - config.warmupDays());
        out.put("adaptiveAdversary", config.adaptiveAdversary());

        Map<String, Object> byType = new LinkedHashMap<>();
        double atRisk = 0;
        double executed = 0;
        int cases = 0;
        int attempts = 0;
        for (var e : scams.entrySet()) {
            Scam s = e.getValue();
            atRisk += s.atRisk;
            executed += s.executed;
            cases += s.cases;
            attempts += s.attempts;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("cases", s.cases);
            m.put("attempts", s.attempts);
            m.put("valueAtRisk", round(s.atRisk));
            m.put("valueExecuted", round(s.executed));
            m.put("valuePreventedShare", share(s.atRisk - Math.min(s.executed, s.atRisk), s.atRisk));
            Map<String, Integer> outcomes = new LinkedHashMap<>();
            s.outcomes.forEach((k, v) -> outcomes.put(k.name(), v));
            m.put("outcomes", outcomes);
            byType.put(e.getKey().name(), m);
        }
        double recovered = frozenByEngine + frozenByPolice;
        Map<String, Object> scam = new LinkedHashMap<>();
        scam.put("cases", cases);
        scam.put("attempts", attempts);
        scam.put("valueAtRisk", round(atRisk));
        scam.put("valueExecuted", round(executed));
        scam.put("valuePreventedShare", share(atRisk - Math.min(executed, atRisk), atRisk));
        scam.put("recoveredFromMules", round(recovered));
        scam.put("recoveredByEngineHolds", round(frozenByEngine));
        scam.put("recoveredByPoliceHolds", round(frozenByPolice));
        scam.put("netLoss", round(Math.max(0, executed - recovered)));
        scam.put("netLossShareOfAtRisk", share(Math.max(0, executed - recovered), atRisk));
        scam.put("byTypology", byType);
        out.put("scams", scam);

        Map<String, Object> legit = new LinkedHashMap<>();
        legit.put("payments", legitPayments);
        legit.put("customers", legitPayers.size());
        legit.put("stepUpsPer1000", per1000(legitStepUps));
        legit.put("holdsPer1000", per1000(legitHolds));
        legit.put("declinesPer1000", per1000(legitDeclines));
        legit.put("interruptedPer1000", per1000(legitStepUps + legitHolds + legitDeclines));
        legit.put("customersAffectedShare", share(legitAffected.size(), legitPayers.size()));
        legit.put("valueDelayedShare", share(legitValueDelayed, legitValue));
        legit.put("meanHoldHours", legitHolds == 0 ? 0 : round(legitHoldHours / legitHolds));
        out.put("legitimate", legit);

        List<Double> ttf = new ArrayList<>();
        int flagged = 0;
        for (var e : muleFirstCredit.entrySet()) {
            Instant flag = earliest(muleFlagged.get(e.getKey()), muleHeld.get(e.getKey()));
            if (flag != null) {
                flagged++;
                ttf.add(Math.max(0, Duration.between(e.getValue(), flag).toMinutes() / 60.0));
            }
        }
        ttf.sort(Double::compare);
        Map<String, Object> mules = new LinkedHashMap<>();
        mules.put("muleAccounts", muleFirstCredit.size());
        mules.put("caughtShare", share(flagged, muleFirstCredit.size()));
        mules.put("heldShare", share(muleHeld.keySet().stream().filter(muleFirstCredit::containsKey).count(), muleFirstCredit.size()));
        mules.put("medianHoursToFlag", ttf.isEmpty() ? null : round(ttf.get(ttf.size() / 2)));
        mules.put("muleInflow", round(muleInflow));
        mules.put("inflowFrozenShare", share(frozenByEngine + frozenByPolice, muleInflow));
        mules.put("legitAccountsMonitored", legitAccountsMonitored);
        mules.put("legitFlaggedPer10000", legitAccountsMonitored == 0 ? 0 : round(10000.0 * legitFlagged / legitAccountsMonitored));
        mules.put("legitWronglyHeld", legitWronglyHeld);
        mules.put("legitWrongHoldDays", round(legitWrongHoldDays));
        out.put("mules", mules);

        if (latencyCount > 0) {
            long[] l = Arrays.copyOf(latencies, latencyCount);
            Arrays.sort(l);
            Map<String, Object> lat = new LinkedHashMap<>();
            lat.put("decisions", latencySeen);
            lat.put("p50Micros", round(l[l.length / 2] / 1000.0));
            lat.put("p99Micros", round(l[(int) Math.min(l.length - 1, Math.floor(l.length * 0.99))] / 1000.0));
            out.put("engineLatency", lat);
        }
        out.put("amountUnit", "multiples of the pack's amountScale (" + scale + " " + config.packId() + " currency)");
        return out;
    }

    private static Instant earliest(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isBefore(b) ? a : b;
    }

    private double per1000(int n) {
        return legitPayments == 0 ? 0 : round(1000.0 * n / legitPayments);
    }

    private static double share(double part, double whole) {
        return whole <= 0 ? 0 : Math.round(10000.0 * part / whole) / 10000.0;
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
