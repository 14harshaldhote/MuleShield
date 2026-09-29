package io.muleshield.sim;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

import io.muleshield.core.ml.BaselineRuleScorer;
import io.muleshield.core.ml.GbmModel;
import io.muleshield.core.ml.Scorer;
import io.muleshield.core.money.Money;
import io.muleshield.core.mule.MuleAssessment;
import io.muleshield.core.mule.MuleEngine;
import io.muleshield.core.mule.MuleFeature;
import io.muleshield.core.mule.MuleRuleScorer;
import io.muleshield.core.payee.NameMatcher;
import io.muleshield.core.payment.AccountRef;
import io.muleshield.core.payment.Party;
import io.muleshield.core.payment.PaymentInstruction;
import io.muleshield.core.payment.PaymentType;
import io.muleshield.core.payment.Scheme;
import io.muleshield.core.payment.SessionSignals;
import io.muleshield.core.policy.PolicyPack;
import io.muleshield.core.policy.PolicyPacks;
import io.muleshield.core.risk.Action;
import io.muleshield.core.risk.PayeeSnapshot;
import io.muleshield.core.risk.PaymentContext;
import io.muleshield.core.risk.PaymentFeature;
import io.muleshield.core.risk.PaymentFeatures;
import io.muleshield.core.risk.ReasonCode;
import io.muleshield.core.risk.RiskDecision;
import io.muleshield.core.risk.RiskEngine;
import io.muleshield.core.state.AccountEvent;
import io.muleshield.core.state.AccountState;

/**
 * A synthetic banking population with realistic everyday payments, scam campaigns against it, and
 * the mule rings that launder the proceeds, run event by event through the same engine code the
 * services use.
 * <p>
 * The world is built in units of the pack's {@code amountScale} (a typical month's take-home pay),
 * so one population can be replayed under India's, the UK's or the EU's rules. The measuring bank
 * ("MSB") holds the simulated customers; mules sit at MSB and at other banks.
 * <p>
 * Everything a victim or a mule does is drawn from a seeded generator separate from the one that
 * reacts to interventions, so every strategy faces the same scams.
 */
final class World {

    static final String OUR_BANK = "MSB";
    static final Instant NEVER = Instant.MAX;

    enum Kind { PERSON, MERCHANT, EMPLOYER, CASH_OUT }

    enum Segment { SALARIED, SHOPKEEPER, STUDENT, RETIREE }

    static final class Account {
        final String key;
        final AccountRef ref;
        final boolean ours;
        final Kind kind;
        final Instant openedAt;
        final String name;
        final int id;
        long balance;
        AccountState state;

        Segment segment;
        double income;
        String deviceId;
        Instant deviceBoundAt;
        Instant simChangedAt;
        final List<Account> contacts = new ArrayList<>();
        final List<Account> shops = new ArrayList<>();
        Account landlord;
        Account supplier;
        Account employer;
        Account relative;
        double rent;
        int rentDay;
        int billDay;
        int supplierDay;
        final Set<String> trusted = new HashSet<>();

        Ring ring;
        int layer;
        boolean mule;
        Instant muleSince;
        boolean forwardScheduled;
        int forwardFailures;
        String heldBy;
        Instant flaggedAt;
        boolean everFlagged;

        Account(String bank, int id, boolean ours, Kind kind, Instant openedAt, String name) {
            this.id = id;
            this.ref = new AccountRef(bank, String.format("%010d", id));
            this.key = ref.key();
            this.ours = ours;
            this.kind = kind;
            this.openedAt = openedAt;
            this.name = name;
        }

        boolean held() {
            return state != null && state.onHold();
        }
    }

    /** A mule ring: one herder's phone runs every account in it. */
    static final class Ring {
        final String device;
        final Instant deviceBoundAt;
        final Instant from;
        final Instant until;
        final boolean slow;
        final List<Account> l1 = new ArrayList<>();
        final List<Account> l2 = new ArrayList<>();
        final List<Account> l3 = new ArrayList<>();

        Ring(String device, Instant deviceBoundAt, Instant from, Instant until, boolean slow) {
            this.device = device;
            this.deviceBoundAt = deviceBoundAt;
            this.from = from;
            this.until = until;
            this.slow = slow;
        }
    }

    static final class ScamCase {
        final Typology typology;
        final Account victim;
        final boolean measured;
        final List<Account> paid = new ArrayList<>();
        Account lastMule;

        ScamCase(Typology typology, Account victim, boolean measured) {
            this.typology = typology;
            this.victim = victim;
            this.measured = measured;
        }
    }

    static final class Intent {
        Account payer;
        Account payee;
        long amount;
        double balanceFraction;
        PaymentType type;
        Typology typology = Typology.LEGIT;
        ScamCase scamCase;
        SessionSignals session;
        NameMatcher.Result nameResult = NameMatcher.Result.MATCH;
        int attempt;
        boolean first = true;
    }

    private record Scheduled(Instant at, long seq, Runnable action) {
    }

    // ------------------------------------------------------------------ setup

    private final SimConfig config;
    private final PolicyPack pack;
    private final Assumptions a;
    private final Strategy strategy;
    private final ZoneId zone;
    private final double unit;
    private final Rng world;
    private final Rng react;
    private final Metrics metrics;
    private final TrainingLog log;
    private final RiskEngine engine;
    private final RiskEngine policyOnly;
    private final MuleEngine muleEngine;
    private final boolean screening;

    private final PriorityQueue<Scheduled> queue = new PriorityQueue<>((x, y) ->
            x.at.equals(y.at) ? Long.compare(x.seq, y.seq) : x.at.compareTo(y.at));
    private long seq;
    private Instant now;
    private final Instant start;
    private final Instant end;
    private final Instant measureFrom;

    private final List<Account> ourPersons = new ArrayList<>();
    private final List<Account> externalPersons = new ArrayList<>();
    private final List<Account> merchants = new ArrayList<>();
    private final List<Account> employers = new ArrayList<>();
    private final List<Ring> rings = new ArrayList<>();
    private final List<Account> allMules = new ArrayList<>();
    private Account cashOut;
    private final Map<String, Map<String, Instant>> devices = new HashMap<>();
    private final Map<String, Integer> reports = new HashMap<>();
    private final Map<String, Double> intel = new HashMap<>();
    private int ids;

    World(SimConfig config) {
        this.config = config;
        PolicyPack loaded = PolicyPacks.load(config.packId());
        this.pack = config.strategy() == Strategy.RISK_BASED_ONLY ? loaded.withoutBlanketDelay() : loaded;
        this.a = config.assumptions();
        this.strategy = config.strategy();
        this.zone = pack.zone();
        this.unit = pack.amountScale().doubleValue() * Math.pow(10, Currency.getInstance(pack.currency()).getDefaultFractionDigits());
        this.world = new Rng(config.seed());
        this.react = new Rng(config.seed() * 31 + 7);
        this.metrics = new Metrics(pack.amountScale().doubleValue());
        this.start = config.start().atStartOfDay(zone).toInstant();
        this.end = start.plus(Duration.ofDays(config.days()));
        this.measureFrom = start.plus(Duration.ofDays(config.warmupDays()));
        this.log = config.trainingOut() == null ? null
                : new TrainingLog(config.trainingOut(), config.seed(), PaymentFeature.NAMES, MuleFeature.NAMES);

        Scorer paymentScorer = config.paymentModel() != null && strategy.usesModels()
                ? loadModel(config, true) : new BaselineRuleScorer();
        Scorer muleScorer = config.muleModel() != null && (strategy.usesModels() || log != null)
                ? loadModel(config, false) : new MuleRuleScorer();
        this.engine = new RiskEngine(pack, paymentScorer);
        this.policyOnly = new RiskEngine(pack, new ZeroScorer());
        this.muleEngine = new MuleEngine(pack, muleScorer);
        this.screening = strategy.usesEngine() || log != null;
        this.now = start;
        populate();
    }

    private static Scorer loadModel(SimConfig config, boolean payment) {
        var path = payment ? config.paymentModel() : config.muleModel();
        try (InputStream in = Files.newInputStream(path)) {
            return GbmModel.load(in, payment ? PaymentFeature.NAMES : MuleFeature.NAMES);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Scores nothing, so only the pack's blanket rules act. */
    private static final class ZeroScorer implements Scorer {
        @Override
        public String version() {
            return "none";
        }

        @Override
        public Score score(double[] features) {
            return new Score(0, Double.NEGATIVE_INFINITY, new double[features.length]);
        }
    }

    private Account account(String bank, boolean ours, Kind kind, Instant opened) {
        int id = ++ids;
        return new Account(bank, id, ours, kind, opened, "Customer " + id);
    }

    /**
     * A generator for one person's day or one scam case. Keying the randomness this way keeps every
     * strategy facing exactly the same behaviour: what one customer does can't shift the draws of another.
     */
    private Rng rngFor(long day, long key) {
        long h = config.seed() * 0x9E3779B97F4A7C15L + day * 0xC2B2AE3D27D4EB4FL + key * 0x165667B19E3779F9L;
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        return new Rng(h);
    }

    private static String externalBank(Rng r) {
        return "XB" + r.i(1, 4);
    }

    private void populate() {
        Rng r = world;
        cashOut = account("CASH", false, Kind.CASH_OUT, start.minus(Duration.ofDays(5000)));
        for (int i = 0; i < 400; i++) {
            merchants.add(account(externalBank(r), false, Kind.MERCHANT, start.minus(Duration.ofDays(r.i(200, 4000)))));
        }
        for (int i = 0; i < 150; i++) {
            employers.add(account(externalBank(r), false, Kind.EMPLOYER, start.minus(Duration.ofDays(r.i(500, 6000)))));
        }
        for (int i = 0; i < config.customers(); i++) {
            Account p = person(externalBank(r), false, r);
            externalPersons.add(p);
        }
        for (int i = 0; i < config.customers(); i++) {
            ourPersons.add(person(OUR_BANK, true, r));
        }
        for (Account p : ourPersons) {
            int n = r.i(3, 8);
            for (int k = 0; k < n; k++) {
                p.contacts.add(r.p(0.6) ? r.pick(ourPersons) : r.pick(externalPersons));
            }
            p.contacts.remove(p);
            if (p.contacts.isEmpty()) {
                p.contacts.add(r.pick(externalPersons));
            }
            for (int k = 0; k < r.i(3, 6); k++) {
                p.shops.add(r.pick(merchants));
            }
            p.relative = r.pick(externalPersons);
            p.employer = r.pick(employers);
            if (p.segment != Segment.RETIREE && r.p(0.4)) {
                p.landlord = r.p(0.5) ? r.pick(ourPersons) : r.pick(externalPersons);
                if (p.landlord == p) {
                    p.landlord = r.pick(externalPersons);
                }
                p.rent = p.income * r.u(0.3, 0.6);
                p.rentDay = r.i(1, 5);
                if (r.p(0.4)) {
                    p.trusted.add(p.landlord.key);
                }
            }
            if (p.segment == Segment.SHOPKEEPER) {
                p.supplier = r.pick(externalPersons);
                p.supplierDay = r.i(1, 7);
                if (r.p(0.7)) {
                    p.trusted.add(p.supplier.key);
                }
            }
            p.billDay = r.i(5, 25);
            if (r.p(0.02)) {
                // Family members sharing one phone: a legitimate "shared device".
                Account other = r.pick(ourPersons);
                if (other != p) {
                    other.deviceId = p.deviceId;
                }
            }
            seedHistory(p, r);
        }
        createRings(r);
    }

    private Account person(String bank, boolean ours, Rng r) {
        double u = r.u();
        Segment seg = u < 0.65 ? Segment.SALARIED : u < 0.75 ? Segment.SHOPKEEPER : u < 0.87 ? Segment.STUDENT : Segment.RETIREE;
        // A few percent are new customers: young accounts are not only mules.
        int ageDays = r.p(0.04) ? r.i(5, 60) : seg == Segment.STUDENT ? r.i(60, 1500) : r.i(120, 5000);
        Account p = account(bank, ours, Kind.PERSON, start.minus(Duration.ofDays(ageDays)));
        p.segment = seg;
        p.income = switch (seg) {
            case SALARIED -> r.logNormal(1.0, 0.5);
            case SHOPKEEPER -> r.logNormal(1.2, 0.5);
            case STUDENT -> r.logNormal(0.35, 0.4);
            case RETIREE -> r.logNormal(0.8, 0.5);
        };
        double months = seg == Segment.RETIREE ? r.u(5, 25) : r.u(1, 5);
        p.balance = minor(p.income * months);
        p.deviceId = "dev-" + p.key;
        p.deviceBoundAt = start.minus(Duration.ofDays(r.i(60, 900)));
        p.state = new AccountState(p.key, p.openedAt);
        return p;
    }

    /** Past payments to habitual payees, so on day one the population has a history, like a real bank's customers. */
    private void seedHistory(Account p, Rng r) {
        List<Account> habitual = new ArrayList<>(p.contacts);
        habitual.addAll(p.shops);
        if (p.landlord != null) {
            habitual.add(p.landlord);
        }
        if (p.supplier != null) {
            habitual.add(p.supplier);
        }
        // Spread over the weeks before day one (never before the account was opened), ending close to it.
        Instant from = p.openedAt.isAfter(start.minus(Duration.ofDays(30))) ? p.openedAt : start.minus(Duration.ofDays(30));
        long span = Math.max(1, Duration.between(from, start).toMinutes());
        List<Instant> times = new ArrayList<>();
        for (int k = 0; k < habitual.size() * 2; k++) {
            times.add(from.plus(Duration.ofMinutes((long) (r.u() * span))));
        }
        times.sort(null);
        int n = 0;
        for (Account payee : habitual) {
            for (int k = 0; k < 2; k++) {
                Instant t = times.get(n++);
                double scaled = payee.kind == Kind.MERCHANT ? r.logNormal(0.006 * p.income, 0.8) : r.logNormal(0.04 * p.income, 0.8);
                p.state.apply(new AccountEvent(AccountEvent.Kind.DEBIT, p.key, t, Math.max(100, minor(scaled)), payee.key,
                        payee.kind == Kind.MERCHANT ? PaymentType.P2M : PaymentType.P2P, 0, 1, p.openedAt), pack);
            }
        }
    }

    private void createRings(Rng r) {
        // Enough mules that each first-layer account receives one or two victim payments a day while active.
        double victimsPerDay = config.customers() * config.scamRate() * baseScamRatePerCustomerDay();
        double l1Needed = Math.max(4, victimsPerDay * 3 / 1.5);
        double ringsPerDay = l1Needed / 4.0 / 15.0;
        // Rings start one after another across the whole run (with jitter), so mules are always available and
        // new, never-seen accounts keep arriving during the measured period, as they do in reality.
        int count = Math.max(config.days() / 4, (int) Math.round(ringsPerDay * (config.days() + 20)));
        double spacing = (config.days() + 20) * 1440.0 / count;
        for (int i = 0; i < count; i++) {
            Instant from = start.minus(Duration.ofDays(20)).plus(Duration.ofMinutes((long) ((i + r.u()) * spacing)));
            Instant until = from.plus(Duration.ofDays(r.i(8, 25)));
            boolean slow = config.adaptiveAdversary() && r.p(0.4);
            Ring ring = new Ring("herder-" + i, from.minus(Duration.ofDays(r.i(1, 5))), from, until, slow);
            for (int k = r.i(3, 5); k > 0; k--) {
                ring.l1.add(mule(ring, 1, r));
            }
            for (int k = 2; k > 0; k--) {
                ring.l2.add(mule(ring, 2, r));
            }
            ring.l3.add(mule(ring, 3, r));
            rings.add(ring);
        }
    }

    private Account mule(Ring ring, int layer, Rng r) {
        boolean ours = r.p(0.35);
        String bank = ours ? OUR_BANK : externalBank(r);
        double u = r.u();
        Instant opened;
        Account m;
        if (u < 0.6) {
            opened = ring.from.minus(Duration.ofDays(r.i(3, 25)));
            m = account(bank, ours, Kind.PERSON, opened);
            m.state = new AccountState(m.key, opened);
        } else {
            // A rented or sold account: years old, dormant for months, then suddenly busy.
            opened = start.minus(Duration.ofDays(r.i(300, 3000)));
            m = account(bank, ours, Kind.PERSON, opened);
            m.state = new AccountState(m.key, opened);
            Instant last = ring.from.minus(Duration.ofDays(u < 0.85 ? r.i(90, 400) : r.i(2, 20)));
            m.state.apply(new AccountEvent(AccountEvent.Kind.CREDIT, m.key, last.minus(Duration.ofDays(3)), minor(0.2), "SEED", PaymentType.P2P, 0, 0, opened), pack);
            m.state.apply(new AccountEvent(AccountEvent.Kind.DEBIT, m.key, last, minor(0.1), "SEED2", PaymentType.P2M, 0, 1, opened), pack);
        }
        m.segment = Segment.STUDENT;
        m.income = 0.3;
        m.balance = minor(r.u(0.001, 0.05));
        m.mule = true;
        m.ring = ring;
        m.layer = layer;
        m.deviceId = ring.device;
        m.deviceBoundAt = ring.deviceBoundAt;
        allMules.add(m);
        return m;
    }

    private static double baseScamRatePerCustomerDay() {
        return 1 / 40000.0 + 1 / 25000.0 + 1 / 50000.0 + 1 / 60000.0;
    }

    // ------------------------------------------------------------------ event loop

    private void at(Instant t, Runnable action) {
        if (t.isBefore(now)) {
            t = now;
        }
        if (t.isBefore(end)) {
            queue.add(new Scheduled(t, seq++, action));
        }
    }

    Metrics run() {
        for (int d = 0; d < config.days(); d++) {
            int day = d;
            at(start.plus(Duration.ofDays(d)), () -> dayStart(day));
        }
        while (!queue.isEmpty()) {
            Scheduled s = queue.poll();
            now = s.at;
            s.action.run();
        }
        now = end;
        settle();
        if (log != null) {
            try {
                log.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return metrics;
    }

    private boolean measured() {
        return !now.isBefore(measureFrom);
    }

    private int dayIndex() {
        return (int) Duration.between(start, now).toDays();
    }

    private long minor(double scaled) {
        // Whole currency units, like real payments.
        return Math.round(scaled * pack.amountScale().doubleValue()) * Math.round(unit / pack.amountScale().doubleValue());
    }

    private double scaled(long minor) {
        return minor / unit;
    }

    // ------------------------------------------------------------------ daily life

    private Instant timeOfDay(Instant dayStart, Rng r) {
        double hour;
        if (r.p(0.04)) {
            hour = (23 + r.u(0, 7)) % 24;
        } else {
            hour = r.p(0.5) ? 12.5 + 2.5 * r.normal() : 19.5 + 2.0 * r.normal();
            hour = Math.max(6, Math.min(22.99, hour));
        }
        return dayStart.plus(Duration.ofSeconds((long) (hour * 3600)));
    }

    private void dayStart(int day) {
        Instant d0 = now;
        LocalDate date = LocalDate.ofInstant(d0, zone);
        int dom = date.getDayOfMonth();
        int dow = date.getDayOfWeek().getValue();

        for (Account p : ourPersons) {
            Rng r = rngFor(day, p.id);
            double m2m = switch (p.segment) {
                case SALARIED -> 1.3;
                case STUDENT -> 1.6;
                case RETIREE -> 0.7;
                case SHOPKEEPER -> 0.8;
            };
            for (int k = r.poisson(m2m); k > 0; k--) {
                Account shop = r.p(0.8) ? r.pick(p.shops) : r.pick(merchants);
                legit(p, shop, r.logNormal(0.006 * p.income, 1.0), PaymentType.P2M, timeOfDay(d0, r), r);
            }
            for (int k = r.poisson(0.15); k > 0; k--) {
                legit(p, r.pick(p.contacts), r.logNormal(0.04 * p.income, 0.9), PaymentType.P2P, timeOfDay(d0, r), r);
            }
            for (int k = r.poisson(0.035); k > 0; k--) {
                Account someone = r.p(0.5) ? r.pick(ourPersons) : r.pick(externalPersons);
                if (someone != p) {
                    legit(p, someone, r.logNormal(0.03 * p.income, 1.0), PaymentType.P2P, timeOfDay(d0, r), r);
                }
            }
            if (r.p(0.0015)) {
                // A genuine large first payment: a second-hand bike, a deposit, a contractor.
                legit(p, r.pick(externalPersons), r.u(0.8, 3.0) * Math.max(p.income, 0.5), PaymentType.P2P, timeOfDay(d0, r), r);
            }
            if (p.landlord != null && dom == p.rentDay) {
                legit(p, p.landlord, p.rent, PaymentType.P2P, timeOfDay(d0, r), r);
            }
            if (dom == p.billDay) {
                legit(p, r.pick(merchants), r.u(0.03, 0.1) * p.income, PaymentType.BILL, timeOfDay(d0, r), r);
            }
            if ((dom == 2 || dom == 16) && r.p(0.5)) {
                legit(p, cashOut, r.u(0.1, 0.3) * p.income, PaymentType.CASH_OUT, timeOfDay(d0, r), r);
            }
            if (dom == 1) {
                double pay = switch (p.segment) {
                    case SALARIED, SHOPKEEPER -> p.segment == Segment.SALARIED ? p.income : 0;
                    case RETIREE -> 0.6 * p.income;
                    case STUDENT -> 0;
                };
                if (pay > 0) {
                    inbound(p.employer, p, pay * r.u(0.95, 1.05), PaymentType.SALARY, d0.plus(Duration.ofHours(r.i(7, 11))));
                }
            }
            if (p.segment == Segment.STUDENT && dom == 3) {
                inbound(p.relative, p, p.income * r.u(0.8, 1.2), PaymentType.P2P, timeOfDay(d0, r));
            } else if (dom == 7 && r.p(0.1)) {
                inbound(p.relative, p, p.income * r.u(0.2, 1.0), PaymentType.P2P, timeOfDay(d0, r));
            }
            if (p.segment == Segment.SHOPKEEPER) {
                for (int k = r.poisson(10); k > 0; k--) {
                    Account buyer = r.p(0.7) ? r.pick(externalPersons) : r.pick(ourPersons);
                    if (buyer != p) {
                        inbound(buyer, p, r.logNormal(0.01, 0.8), PaymentType.P2P, timeOfDay(d0, r));
                    }
                }
                if (dow == p.supplierDay) {
                    legit(p, p.supplier, r.u(1.0, 3.0), PaymentType.P2P, timeOfDay(d0, r), r);
                }
            }
            if ((p.segment == Segment.SALARIED || p.segment == Segment.STUDENT) && r.p(0.0008)) {
                groupCollection(p, d0, r);
            }
            if (r.p(1 / 700.0)) {
                Instant t = timeOfDay(d0, r);
                boolean sim = r.p(0.35);
                at(t, () -> {
                    p.deviceId = "dev-" + p.key + "-" + dayIndex();
                    p.deviceBoundAt = now;
                    if (sim) {
                        p.simChangedAt = now;
                    }
                });
            }
        }
        startScams(day, d0);
    }

    /** A trip organiser collects from friends, then pays the travel agent: fan-in and fast fan-out, but legitimate. */
    private void groupCollection(Account p, Instant d0, Rng r) {
        Instant t = d0.plus(Duration.ofHours(r.i(9, 15)));
        int k = r.i(4, 10);
        double each = r.u(0.05, 0.2);
        for (int i = 0; i < k; i++) {
            Account friend = r.p(0.5) ? r.pick(p.contacts) : r.pick(externalPersons);
            if (friend != p) {
                inbound(friend, p, each * r.u(0.9, 1.1), PaymentType.P2P, t.plus(Duration.ofMinutes(r.i(0, 240))));
            }
        }
        legit(p, r.pick(externalPersons), each * k * 0.9, PaymentType.P2P, t.plus(Duration.ofMinutes(r.i(300, 600))), r);
    }

    private void legit(Account payer, Account payee, double scaledAmount, PaymentType type, Instant t, Rng r) {
        if (payee == payer || scaledAmount <= 0) {
            return;
        }
        Intent in = new Intent();
        in.payer = payer;
        in.payee = payee;
        in.amount = Math.max(unitMinor(), minor(scaledAmount));
        in.type = type;
        boolean onCall = r.p(type == PaymentType.P2P ? 0.05 : 0.015);
        boolean remote = r.p(0.002);
        double u1 = r.u();
        double u2 = r.u();
        in.nameResult = payer.state.knowsPayee(payee.key) || u1 < 0.93 ? NameMatcher.Result.MATCH
                : u2 < 0.7 ? NameMatcher.Result.CLOSE_MATCH : NameMatcher.Result.NO_MATCH;
        at(t, () -> {
            in.session = new SessionSignals(payer.deviceId, payer.deviceBoundAt,
                    payer.simChangedAt != null && Duration.between(payer.simChangedAt, now).toDays() < 30 ? payer.simChangedAt : null,
                    remote, onCall);
            attempt(in);
        });
    }

    private long unitMinor() {
        return Math.round(unit / pack.amountScale().doubleValue());
    }

    /** Money arriving from another bank's customer or an employer: that bank decides, MSB only receives. */
    private void inbound(Account payer, Account payee, double scaledAmount, PaymentType type, Instant t) {
        Intent in = new Intent();
        in.payer = payer;
        in.payee = payee;
        in.amount = Math.max(unitMinor(), minor(scaledAmount));
        in.type = type;
        at(t, () -> {
            in.session = SessionSignals.trustedDevice(payer.deviceId, payer.deviceBoundAt);
            if (payer.ours) {
                attempt(in);
            } else {
                payer.balance += in.amount;   // other banks' customers are funded by the rest of the economy
                execute(in, null);
            }
        });
    }

    // ------------------------------------------------------------------ scams

    private void startScams(int day, Instant d0) {
        Rng r = rngFor(day, -1);
        double n = ourPersons.size() * config.scamRate();
        int da = r.poisson(n / 40000.0);
        int inv = r.poisson(n / 25000.0);
        int ra = r.poisson(n / 50000.0);
        int ato = r.poisson(n / 60000.0);
        for (int k = 0; k < da; k++) {
            Rng c = rngFor(day, -1000 - k);
            digitalArrest(victim(c, Segment.RETIREE, 3.0), d0, c);
        }
        for (int k = 0; k < inv; k++) {
            Rng c = rngFor(day, -2000 - k);
            investment(victim(c, Segment.SALARIED, 1.5), d0, c);
        }
        for (int k = 0; k < ra; k++) {
            Rng c = rngFor(day, -3000 - k);
            remoteAccess(victim(c, null, 1), d0, c);
        }
        for (int k = 0; k < ato; k++) {
            Rng c = rngFor(day, -4000 - k);
            simSwap(victim(c, null, 1), d0, c);
        }
    }

    private Account victim(Rng r, Segment favoured, double weight) {
        for (int tries = 0; tries < 20; tries++) {
            Account p = r.pick(ourPersons);
            if (favoured == null || p.segment == favoured || r.p(1 / weight)) {
                return p;
            }
        }
        return r.pick(ourPersons);
    }

    private ScamCase open(Typology t, Account victim, Instant first) {
        ScamCase c = new ScamCase(t, victim, !first.isBefore(measureFrom));
        if (c.measured) {
            metrics.scamCase(t);
        }
        return c;
    }

    private Account firstLayerMule(Instant t, Rng r, ScamCase c) {
        List<Ring> active = rings.stream().filter(g -> !g.from.isAfter(t) && g.until.isAfter(t)).toList();
        Ring ring = active.isEmpty()
                ? rings.stream().filter(g -> !g.from.isAfter(t)).reduce((x, y) -> y).orElse(rings.getFirst())
                : r.pick(active);
        Account m = r.pick(ring.l1);
        c.lastMule = m;
        return m;
    }

    private Intent scamIntent(ScamCase c, Account payee, double scaledAmount, double balanceFraction, SessionSignals s, Rng r) {
        Intent in = new Intent();
        in.payer = c.victim;
        in.payee = payee;
        in.amount = scaledAmount > 0 ? minor(scaledAmount) : 0;
        in.balanceFraction = balanceFraction;
        in.type = PaymentType.P2P;
        in.typology = c.typology;
        in.scamCase = c;
        in.session = s;
        // Victims are usually given the mule's real name; a mismatch warning is often explained away by the scammer.
        in.nameResult = c.typology == Typology.SIM_SWAP_ATO || r.p(0.7) ? NameMatcher.Result.MATCH
                : r.p(0.35) ? NameMatcher.Result.CLOSE_MATCH : NameMatcher.Result.NO_MATCH;
        return in;
    }

    private SessionSignals victimSession(Account v, boolean onCall, boolean remote) {
        return new SessionSignals(v.deviceId, v.deviceBoundAt, null, remote, onCall);
    }

    /** Adapted scammers split a transfer into several just under the ₹10,000 delay threshold (0.4 of the scale in India). */
    private List<Double> maybeStructure(double scaledAmount, Rng r) {
        boolean split = r.p(0.5);
        double[] sizes = new double[8];
        for (int i = 0; i < sizes.length; i++) {
            sizes[i] = r.u(0.36, 0.396);
        }
        if (!config.adaptiveAdversary() || !split || scaledAmount < 0.4) {
            return List.of(scaledAmount);
        }
        List<Double> parts = new ArrayList<>();
        double left = scaledAmount;
        while (left > 0.01 && parts.size() < sizes.length) {
            double part = Math.min(left, sizes[parts.size()]);
            parts.add(part);
            left -= part;
        }
        return parts;
    }

    private void digitalArrest(Account v, Instant d0, Rng r) {
        int sessions = r.i(1, 3);
        Instant t = d0.plus(Duration.ofHours(r.i(9, 16))).plus(Duration.ofMinutes(r.i(0, 59)));
        ScamCase c = open(Typology.DIGITAL_ARREST, v, t);
        boolean onCall = r.p(0.75);
        boolean remote = r.p(0.1);
        Instant last = t;
        for (int s = 0; s < sessions; s++) {
            Instant ts = t.plus(Duration.ofDays(s)).plus(Duration.ofMinutes(r.i(0, 120)));
            for (int k = r.i(1, 2); k > 0; k--) {
                double fraction = r.u(0.4, 0.7);
                double estimate = scaled(v.balance) * fraction;
                List<Double> parts = maybeStructure(estimate, r);
                for (double part : parts) {
                    Account mule = firstLayerMule(ts, r, c);
                    Intent in = scamIntent(c, mule, parts.size() > 1 ? part : 0, parts.size() > 1 ? 0 : fraction,
                            victimSession(v, onCall, remote), r);
                    at(ts, () -> attempt(in));
                    ts = ts.plus(Duration.ofMinutes(r.i(3, 8)));
                }
                ts = ts.plus(Duration.ofMinutes(r.i(20, 90)));
                last = ts;
            }
        }
        report(c, last, 0.6, Duration.ofHours(2), Duration.ofHours(48), r);
    }

    private void investment(Account v, Instant d0, Rng r) {
        int n = r.i(3, 8);
        Instant t = timeOfDay(d0, r);
        ScamCase c = open(Typology.INVESTMENT, v, t);
        Account mule = firstLayerMule(t, r, c);
        double amount = r.u(0.1, 0.3) * Math.max(0.5, v.income);
        boolean whitelist = r.p(config.adaptiveAdversary() ? 0.7 : 0.3);
        for (int i = 0; i < n; i++) {
            Account payee = mule;
            List<Double> parts = maybeStructure(amount, r);
            Instant ti = t;
            for (double part : parts) {
                Intent in = scamIntent(c, payee, part, 0, victimSession(v, r.p(0.15), r.p(0.05)), r);
                boolean trust = i > 0 && whitelist;
                at(ti, () -> {
                    if (trust) {
                        v.trusted.add(payee.key);
                    }
                    attempt(in);
                });
                ti = ti.plus(Duration.ofMinutes(r.i(3, 10)));
            }
            t = t.plus(Duration.ofHours(r.i(24, 120)));
            amount *= r.u(1.3, 1.8);
            if (r.p(0.5)) {
                mule = firstLayerMule(t, r, c);
            }
        }
        report(c, t, 0.4, Duration.ofDays(3), Duration.ofDays(30), r);
    }

    private void remoteAccess(Account v, Instant d0, Rng r) {
        Instant t = d0.plus(Duration.ofHours(r.i(10, 20)));
        ScamCase c = open(Typology.REMOTE_ACCESS, v, t);
        boolean remote = r.p(0.75);
        boolean onCall = r.p(0.7);
        for (int k = r.i(1, 2); k > 0; k--) {
            double amount = Math.min(r.u(0.2, 1.5), 0.8 * scaled(v.balance));
            for (double part : maybeStructure(amount, r)) {
                Intent in = scamIntent(c, firstLayerMule(t, r, c), part, 0, victimSession(v, onCall, remote), r);
                at(t, () -> attempt(in));
                t = t.plus(Duration.ofMinutes(r.i(3, 15)));
            }
        }
        report(c, t, 0.5, Duration.ofHours(1), Duration.ofHours(24), r);
    }

    private void simSwap(Account v, Instant d0, Rng r) {
        boolean night = r.p(0.6);
        Instant t = night ? d0.plus(Duration.ofMinutes(r.i(0, 240))) : d0.plus(Duration.ofHours(r.i(10, 18)));
        ScamCase c = open(Typology.SIM_SWAP_ATO, v, t);
        Instant sim = t.minus(Duration.ofMinutes(r.i(120, 1440)));
        Instant bound = t.minus(Duration.ofMinutes(r.i(10, 180)));
        SessionSignals fraudster = new SessionSignals("fraud-" + v.key, bound, sim, false, false);
        int n = r.i(3, 6);
        double total = 0.9 * scaled(v.balance);
        List<Double> parts = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            parts.addAll(maybeStructure(total / n, r));
        }
        for (double part : parts) {
            Intent in = scamIntent(c, firstLayerMule(t, r, c), part, 0, fraudster, r);
            at(t, () -> attempt(in));
            t = t.plus(Duration.ofMinutes(r.i(2, 10)));
        }
        report(c, t, 0.8, Duration.ofHours(1), Duration.ofHours(12), r);
    }

    private void report(ScamCase c, Instant after, double probability, Duration min, Duration max, Rng r) {
        if (!r.p(probability)) {
            return;
        }
        Instant t = after.plus(Duration.ofMinutes((long) r.u(min.toMinutes(), max.toMinutes())));
        at(t, () -> {
            for (Account m : new HashSet<>(c.paid)) {
                reports.merge(m.key, 1, Integer::sum);
                if (m.state != null) {
                    m.state.apply(AccountEvent.scamReport(m.key, now), pack);
                }
                screen(m);
                // Today's process, with or without MuleShield: the complaint reaches the receiving bank through the
                // police or the national portal, and the bank freezes what is left a day or two later.
                Instant freeze = now.plus(Duration.ofMinutes((long) react.u(12 * 60, 48 * 60)));
                at(freeze, () -> hold(m, "POLICE"));
            }
        });
    }

    // ------------------------------------------------------------------ payments

    private void attempt(Intent in) {
        Account payer = in.payer;
        Account payee = in.payee;
        if (payer.held()) {
            // The debit hold is enforced on the ledger: nothing leaves a held account.
            if (!payer.mule && measured() && in.typology == Typology.LEGIT) {
                metrics.legit(payer.key, scaled(in.amount));
                metrics.legitFriction(payer.key, false, false, true, scaled(in.amount), 0);
            }
            return;
        }
        long amount = in.balanceFraction > 0 ? (long) (payer.balance * in.balanceFraction) : in.amount;
        amount = Math.min(amount, payer.balance);
        amount = amount / unitMinor() * unitMinor();
        if (amount < unitMinor()) {
            return;
        }
        in.amount = amount;
        if (!payer.ours && !strategy.consortium()) {
            // Another bank's customer, and that bank runs no screening of its own.
            execute(in, null);
            return;
        }
        boolean measured = measured();
        double value = scaled(amount);
        if (in.typology.isScam() && in.scamCase.measured) {
            metrics.scamAttempt(in.typology, value, in.first);
        } else if (in.typology == Typology.LEGIT && measured) {
            metrics.legit(payer.key, value);
        }

        PaymentContext ctx = context(in);
        if (log != null && measured && in.typology != Typology.MULE_LAYERING) {
            log.payment(PaymentFeatures.extract(ctx, pack), in.typology, dayIndex());
        }
        RiskDecision d = decide(ctx);
        switch (d.action()) {
            case DECLINE -> declined(in, d);
            case HOLD -> held(in, d);
            case ALLOW -> {
                if (d.stepUp()) {
                    stepUp(in, d);
                } else {
                    execute(in, d);
                }
            }
        }
    }

    private PaymentInstruction payment(Intent in) {
        return new PaymentInstruction(UUID.randomUUID(), "E2E", new Party(in.payer.name, in.payer.ref),
                new Party(in.payee.name, in.payee.ref), new Money(in.amount, pack.currency()), scheme(), in.type, "OTHR", now);
    }

    private PaymentContext context(Intent in) {
        Account payer = in.payer;
        Account payee = in.payee;
        var payment = payment(in);
        var payerSnap = payer.state.payerSnapshot(now, new Money(payer.balance, pack.currency()), payee.key,
                payer.trusted.contains(payee.key), false);
        return new PaymentContext(payment, in.session, payerSnap, payeeSnapshot(payee), nameCheck(in), now);
    }

    private PayeeSnapshot payeeSnapshot(Account payee) {
        int reported = reports.getOrDefault(payee.key, 0);
        if (payee.ours && payee.state != null) {
            PayeeSnapshot s = payee.state.payeeSnapshot(now, 0);
            return new PayeeSnapshot(s.accountOpenedAt(), s.muleScore(), s.onDebitHold(), s.inCount24h(), s.distinctSenders24h(),
                    Math.max(s.scamReports30d(), reported), 0);
        }
        // Another bank's account: only the national suspect registry and what the consortium shares are visible.
        double shared = intel.getOrDefault(payee.key, 0.0);
        boolean knownHeld = strategy.consortium() && payee.held();
        return new PayeeSnapshot(null, 0, knownHeld, 0, 0, reported, shared);
    }

    private NameMatcher.Check nameCheck(Intent in) {
        return switch (pack.payeeVerification().mode()) {
            case NONE, NAME_DISPLAY -> new NameMatcher.Check(NameMatcher.Result.NOT_POSSIBLE, in.payee.name, 0);
            default -> new NameMatcher.Check(in.nameResult, in.payee.name, 1.0);
        };
    }

    private Scheme scheme() {
        return switch (pack.jurisdiction()) {
            case "IN" -> Scheme.UPI;
            case "UK" -> Scheme.FPS;
            case "EU" -> Scheme.SCT_INST;
            case "SG" -> Scheme.FAST;
            case "AU" -> Scheme.NPP;
            default -> Scheme.RTP;
        };
    }

    private RiskDecision decide(PaymentContext ctx) {
        return switch (strategy) {
            case NONE -> allow(ctx);
            case LEGACY_RULES -> {
                boolean risky = !ctx.payer().knowsPayee() && pack.scaled(ctx.payment().amount()) >= 2.0;
                yield risky ? new RiskDecision(ctx.payment().uetr(), Action.HOLD, false, now.plus(Duration.ofHours(24)), true,
                        true, 1, List.of(), null, Money.zero(pack.currency()), "legacy", pack.id(), null) : allow(ctx);
            }
            case POLICY_ONLY -> policyOnly.assess(ctx);
            default -> {
                long t0 = System.nanoTime();
                RiskDecision d = engine.assess(ctx);
                if (measured()) {
                    metrics.latency(System.nanoTime() - t0);
                }
                yield d;
            }
        };
    }

    private RiskDecision allow(PaymentContext ctx) {
        return new RiskDecision(ctx.payment().uetr(), Action.ALLOW, false, null, false, false, 0, List.of(), null,
                Money.zero(pack.currency()), "none", pack.id(), null);
    }

    private boolean measuredScam(Intent in) {
        return in.typology.isScam() && in.scamCase.measured;
    }

    private void declined(Intent in, RiskDecision d) {
        if (measuredScam(in)) {
            metrics.scamOutcome(in.typology, d.has(ReasonCode.PAYEE_ON_HOLD)
                    ? Metrics.Outcome.STOPPED_PAYEE_HELD : Metrics.Outcome.DECLINED, scaled(in.amount));
        }
        if (in.typology.isScam()) {
            if (in.attempt < 2 && react.p(a.retryAfterDecline())) {
                // The scammer has the victim try again: smaller, to another mule.
                Intent retry = new Intent();
                retry.payer = in.payer;
                retry.payee = firstLayerMule(now, react, in.scamCase);
                double next = scaled(in.amount) * react.u(0.3, 0.8);
                if (config.adaptiveAdversary()) {
                    next = Math.min(next, react.u(0.36, 0.396));
                }
                retry.amount = minor(next);
                retry.type = in.type;
                retry.typology = in.typology;
                retry.scamCase = in.scamCase;
                retry.session = in.session;
                retry.nameResult = in.nameResult;
                retry.attempt = in.attempt + 1;
                retry.first = false;
                at(now.plus(Duration.ofMinutes(react.i(10, 60))), () -> attempt(retry));
            }
        } else if (in.typology == Typology.LEGIT) {
            if (measured()) {
                metrics.legitFriction(in.payer.key, false, false, true, scaled(in.amount), 0);
            }
        } else {
            retryForward(in.payer);
        }
    }

    private void stepUp(Intent in, RiskDecision d) {
        if (in.typology.isScam()) {
            double stop = in.typology.isUnauthorised() ? a.passkeyStopsTakeover() : a.passkeyStopsScam();
            if (react.p(stop)) {
                if (measuredScam(in)) {
                    metrics.scamOutcome(in.typology, Metrics.Outcome.STOPPED_BY_PASSKEY, scaled(in.amount));
                }
                return;
            }
        } else if (in.typology == Typology.LEGIT && measured()) {
            metrics.legitFriction(in.payer.key, true, false, false, scaled(in.amount), 0);
        }
        execute(in, d);
    }

    private void held(Intent in, RiskDecision d) {
        Instant release = d.reviewRequired() ? min(d.holdUntil(), now.plus(a.reviewDelay())) : d.holdUntil();
        if (in.typology.isScam()) {
            double cancel = d.reviewRequired() ? a.stopAfterBankCall()
                    : hasScamWarning(d) ? a.cancelAfterWarning() : a.cancelAfterDelay();
            if (d.stepUp() && react.p(in.typology.isUnauthorised() ? a.passkeyStopsTakeover() : a.passkeyStopsScam())) {
                if (measuredScam(in)) {
                    metrics.scamOutcome(in.typology, Metrics.Outcome.STOPPED_BY_PASSKEY, scaled(in.amount));
                }
                return;
            }
            if (react.p(cancel)) {
                if (measuredScam(in)) {
                    metrics.scamOutcome(in.typology, Metrics.Outcome.CANCELLED_IN_HOLD, scaled(in.amount));
                }
                return;
            }
        } else if (in.typology == Typology.LEGIT && measured()) {
            metrics.legitFriction(in.payer.key, d.stepUp(), true, false, scaled(in.amount),
                    Duration.between(now, release).toMinutes() / 60.0);
        }
        at(release, () -> {
            // During the hold the receiving account may have been reported or frozen: check again before releasing.
            if (knownHeld(in.payee) || in.payer.held()) {
                if (measuredScam(in)) {
                    metrics.scamOutcome(in.typology, Metrics.Outcome.STOPPED_PAYEE_HELD, scaled(in.amount));
                }
                if (in.typology == Typology.MULE_LAYERING) {
                    retryForward(in.payer);
                }
                return;
            }
            in.amount = Math.min(in.amount, in.payer.balance);
            if (in.amount >= unitMinor()) {
                execute(in, d);
            }
        });
    }

    private boolean knownHeld(Account payee) {
        return payee.held() && (payee.ours || strategy.consortium());
    }

    private static boolean hasScamWarning(RiskDecision d) {
        return d.reasons().stream().anyMatch(r -> !r.isPolicy() && r.code().customerText() != null);
    }

    private static Instant min(Instant x, Instant y) {
        return x.isBefore(y) ? x : y;
    }

    private void execute(Intent in, RiskDecision d) {
        Account payer = in.payer;
        Account payee = in.payee;
        long amount = in.amount;
        payer.balance -= amount;
        payee.balance += amount;
        int deviceAccounts = deviceUse(payer);
        double payerRisk = payer.state == null ? 0 : payer.state.muleScore();
        for (AccountEvent e : AccountEvent.of(payment(in), now, payerRisk, deviceAccounts, payer.openedAt, payee.openedAt)) {
            Account target = e.kind() == AccountEvent.Kind.DEBIT ? payer : payee;
            if (target.state != null) {
                target.state.apply(e, pack);
            }
        }
        if (measuredScam(in)) {
            metrics.scamOutcome(in.typology, Metrics.Outcome.EXECUTED, scaled(amount));
        }
        if (in.scamCase != null) {
            in.scamCase.paid.add(payee);
        }
        if (payee.mule) {
            if (payee.muleSince == null) {
                payee.muleSince = now;
                if (payee.ours && measured()) {
                    metrics.muleFirstCredit.put(payee.key, now);
                }
            }
            if (measured()) {
                metrics.muleInflow += in.typology.isScam() ? scaled(amount) : 0;
            }
            scheduleForward(payee);
        }
        if (screening) {
            screen(payer);
            screen(payee);
        }
    }

    private int deviceUse(Account payer) {
        if (payer.deviceId == null) {
            return 1;
        }
        Map<String, Instant> used = devices.computeIfAbsent(payer.ref.bankId() + "|" + payer.deviceId, k -> new HashMap<>());
        used.put(payer.key, now);
        Instant cutoff = now.minus(Duration.ofDays(30));
        used.values().removeIf(t -> t.isBefore(cutoff));
        return used.size();
    }

    // ------------------------------------------------------------------ mules

    private void scheduleForward(Account m) {
        if (m.forwardScheduled) {
            return;
        }
        m.forwardScheduled = true;
        Duration dwell = m.ring.slow ? Duration.ofMinutes(react.i(180, 600)) : Duration.ofMinutes(react.i(5, 45));
        at(now.plus(dwell), () -> forward(m));
    }

    private void retryForward(Account m) {
        m.forwardFailures++;
        m.forwardScheduled = false;
        if (m.forwardFailures <= 4) {
            m.forwardScheduled = true;
            at(now.plus(Duration.ofMinutes(react.i(60, 180))), () -> forward(m));
        }
    }

    private void forward(Account m) {
        m.forwardScheduled = false;
        if (m.held()) {
            return;
        }
        long available = m.balance - unitMinor() * 100;
        if (available < unitMinor() * 500) {
            return;
        }
        long amount = Math.round(available * 0.97);
        List<Account> next = m.layer == 1 ? m.ring.l2 : m.layer == 2 && react.p(0.5) ? m.ring.l3 : List.of();
        if (next.isEmpty() || m.forwardFailures >= 3) {
            // Cash out: withdrawals of up to about a month's pay each, as ATM and cardless limits allow.
            long chunk = minor(0.8);
            Instant t = now;
            while (amount > 0) {
                long part = Math.min(amount, chunk);
                amount -= part;
                Intent in = layering(m, cashOut, part, PaymentType.CASH_OUT);
                at(t, () -> attempt(in));
                t = t.plus(Duration.ofMinutes(react.i(2, 20)));
            }
            return;
        }
        long share = amount / next.size();
        Instant t = now;
        for (Account n : next) {
            Intent in = layering(m, n, share, PaymentType.P2P);
            at(t, () -> attempt(in));
            t = t.plus(Duration.ofMinutes(react.i(1, 10)));
        }
    }

    private Intent layering(Account m, Account to, long amount, PaymentType type) {
        Intent in = new Intent();
        in.payer = m;
        in.payee = to;
        in.amount = amount;
        in.type = type;
        in.typology = Typology.MULE_LAYERING;
        in.session = new SessionSignals(m.ring.device, m.ring.deviceBoundAt, null, false, false);
        return in;
    }

    /** Scores an account after money moved. Only the account's own bank acts on it; the consortium shares what it finds. */
    private void screen(Account acct) {
        if (acct.kind != Kind.PERSON || acct.state == null) {
            return;
        }
        boolean actsOnIt = strategy.usesEngine() && (acct.ours || strategy.consortium());
        boolean observed = acct.ours || strategy.consortium() || log != null;
        if (!observed) {
            return;
        }
        MuleAssessment m = muleEngine.assess(acct.state, now, 0);
        acct.state.setMuleScore(m.score());
        if (log != null && acct.ours && measured()) {
            log.mule(m.features(), acct.mule && acct.muleSince != null, acct.key, dayIndex());
        }
        var t = pack.thresholds();
        if (!acct.ours && (strategy.consortium() || log != null)) {
            // What other banks share: a pseudonymous "suspected mule" signal once their model flags the account.
            if (m.score() >= t.muleFlag()) {
                intel.merge(acct.key, 0.6 * m.score(), Math::max);
            }
        }
        if (!actsOnIt) {
            return;
        }
        AccountState.Status status = acct.state.status();
        if (m.action() == MuleAssessment.Action.AUTO_HOLD && status != AccountState.Status.ON_HOLD
                && status != AccountState.Status.CONFIRMED_MULE) {
            flagged(acct);
            hold(acct, "ENGINE");
            at(now.plus(a.reviewDelay()), () -> review(acct));
        } else if (m.action() == MuleAssessment.Action.FLAG && status == AccountState.Status.NONE) {
            flagged(acct);
            acct.state.setStatus(AccountState.Status.FLAGGED);
            at(now.plus(a.reviewDelay()), () -> review(acct));
        }
    }

    private void flagged(Account acct) {
        if (acct.flaggedAt == null) {
            acct.flaggedAt = now;
        }
        if (acct.ours && measured()) {
            if (acct.mule) {
                metrics.muleFlagged.putIfAbsent(acct.key, now);
            } else if (!acct.everFlagged) {
                metrics.legitFlagged++;
            }
        }
        acct.everFlagged = true;
    }

    private void review(Account acct) {
        AccountState.Status status = acct.state.status();
        if (status == AccountState.Status.CLEARED || status == AccountState.Status.CONFIRMED_MULE) {
            return;
        }
        boolean confirm = acct.mule ? react.p(a.analystConfirmsMule()) : !react.p(a.analystClearsLegit());
        if (confirm) {
            hold(acct, acct.heldBy == null ? "ENGINE" : acct.heldBy);
            acct.state.setStatus(AccountState.Status.CONFIRMED_MULE);
            if (strategy.consortium()) {
                intel.put(acct.key, 1.0);
            }
            if (!acct.mule) {
                // A wrong call: the customer gets the account back after contacting the bank.
                if (measured() && acct.ours) {
                    metrics.legitWronglyHeld++;
                    metrics.legitWrongHoldDays += a.wrongHoldDuration().toHours() / 24.0;
                }
                at(now.plus(a.wrongHoldDuration()), () -> release(acct));
            }
        } else {
            if (acct.held() && !acct.mule && acct.ours && measured()) {
                metrics.legitWronglyHeld++;
                metrics.legitWrongHoldDays += a.reviewDelay().toHours() / 24.0;
            }
            release(acct);
        }
    }

    private void release(Account acct) {
        acct.state.setStatus(AccountState.Status.CLEARED);
        acct.heldBy = null;
        intel.remove(acct.key);
    }

    private void hold(Account acct, String by) {
        if (acct.state == null || acct.held()) {
            return;
        }
        if ("POLICE".equals(by) && !acct.mule) {
            return;
        }
        acct.state.setStatus(AccountState.Status.ON_HOLD);
        acct.heldBy = by;
        if (acct.mule && acct.ours && measured()) {
            metrics.muleHeld.putIfAbsent(acct.key, now);
        }
        if (strategy.consortium()) {
            intel.put(acct.key, 1.0);
        }
    }

    /** At the end: money still sitting in held mule accounts can be returned to victims. */
    private void settle() {
        for (Account m : allMules) {
            if (m.held() && m.muleSince != null && !m.muleSince.isBefore(measureFrom)) {
                if ("ENGINE".equals(m.heldBy)) {
                    metrics.frozenByEngine += scaled(m.balance);
                } else {
                    metrics.frozenByPolice += scaled(m.balance);
                }
            }
        }
        metrics.legitAccountsMonitored = ourPersons.size();
    }
}
