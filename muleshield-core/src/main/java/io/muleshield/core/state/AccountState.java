package io.muleshield.core.state;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;

import io.muleshield.core.money.Money;
import io.muleshield.core.payment.PaymentType;
import io.muleshield.core.policy.PolicyPack;
import io.muleshield.core.risk.PayeeSnapshot;
import io.muleshield.core.risk.PayerSnapshot;

/**
 * Everything MuleShield remembers about one account, updated one {@link AccountEvent} at a time: the
 * payer-side history that scores outgoing payments, and the receiving-side flows that reveal a
 * mule. All windows are bounded in time and size, so the state of a busy merchant account stays a
 * few kilobytes (it is a Kafka Streams state-store value in the service).
 * <p>
 * The pass-through measure matches outgoing money to incoming money first-in-first-out: a debit
 * spends the oldest unspent credit lots, and the time each piece stayed is its dwell time. Money
 * that arrives from strangers and leaves within the hour is the mule signature every FATF and RBI
 * advisory describes; money that sits for days is someone's salary.
 */
@JsonAutoDetect(fieldVisibility = Visibility.ANY, getterVisibility = Visibility.NONE,
        isGetterVisibility = Visibility.NONE, setterVisibility = Visibility.NONE)
public final class AccountState {

    static final Duration WINDOW = Duration.ofHours(24);
    static final Duration HOUR = Duration.ofHours(1);
    static final Duration REPORT_WINDOW = Duration.ofDays(30);
    static final Duration LOT_TTL = Duration.ofDays(7);
    static final Duration REACTIVATION_WINDOW = Duration.ofDays(7);
    static final int MAX_EVENTS = 400;
    static final int MAX_KNOWN = 256;
    static final int MAX_LOTS = 200;

    public enum Status { NONE, FLAGGED, ON_HOLD, CLEARED, CONFIRMED_MULE }

    record Out(Instant at, long amountMinor, String payee, boolean newPayee, PaymentType type) {
    }

    record In(Instant at, long amountMinor, String sender, boolean newSender, double senderRisk) {
    }

    record Lot(Instant at, long remainingMinor) {
    }

    /** A piece of inbound value spent by a debit. */
    record Match(Instant creditAt, Instant debitAt, long amountMinor) {
    }

    private String account;
    private Instant openedAt;
    private Instant firstSeenAt;
    private Instant lastEventAt;
    private Instant burstStartAt;
    private double burstGapDays;
    private int nightEvents24h;

    private double logMean;
    private double logVar;
    private int history;
    private ArrayDeque<Out> outs = new ArrayDeque<>();
    private LinkedHashSet<String> knownPayees = new LinkedHashSet<>();

    private ArrayDeque<In> ins = new ArrayDeque<>();
    private ArrayDeque<Lot> lots = new ArrayDeque<>();
    private ArrayDeque<Match> matches = new ArrayDeque<>();
    private LinkedHashSet<String> knownSenders = new LinkedHashSet<>();
    private ArrayDeque<Instant> reports = new ArrayDeque<>();
    private ArrayDeque<Instant> nights = new ArrayDeque<>();
    private int deviceAccounts;
    private Instant deviceAccountsAt;

    private double muleScore;
    private Status status = Status.NONE;

    public AccountState() {
    }

    public AccountState(String account, Instant openedAt) {
        this.account = account;
        this.openedAt = openedAt;
    }

    // ------------------------------------------------------------------ updates

    public AccountState apply(AccountEvent e, PolicyPack pack) {
        if (account == null) {
            account = e.account();
        }
        if (openedAt == null && e.accountOpenedAt() != null) {
            openedAt = e.accountOpenedAt();
        }
        if (firstSeenAt == null) {
            firstSeenAt = e.at();
        }
        if (e.kind() == AccountEvent.Kind.SCAM_REPORT) {
            reports.addLast(e.at());
            evict(e.at());
            return this;
        }
        if (lastEventAt != null && Duration.between(lastEventAt, e.at()).compareTo(WINDOW) > 0) {
            burstStartAt = e.at();
            burstGapDays = Duration.between(lastEventAt, e.at()).toHours() / 24.0;
        }
        lastEventAt = e.at();
        if (isNight(e.at(), pack.zone())) {
            nights.addLast(e.at());
        }
        switch (e.kind()) {
            case DEBIT -> debit(e, pack);
            case CREDIT -> credit(e);
            default -> throw new IllegalStateException();
        }
        evict(e.at());
        return this;
    }

    private void debit(AccountEvent e, PolicyPack pack) {
        double x = Math.log10(Math.max(e.amountMinor() / minorPerMajor(pack) / pack.amountScale().doubleValue(), 1e-4));
        if (history == 0) {
            logMean = x;
            logVar = 0;
        } else {
            // Exponentially weighted, so a customer's "usual" follows them; a plain mean for the first ten.
            double a = Math.max(0.1, 1.0 / (history + 1));
            double d = x - logMean;
            logMean += a * d;
            logVar = (1 - a) * (logVar + a * d * d);
        }
        history++;
        boolean newPayee = e.counterparty() != null && !knownPayees.contains(e.counterparty());
        outs.addLast(new Out(e.at(), e.amountMinor(), e.counterparty(), newPayee, e.type()));
        remember(knownPayees, e.counterparty());
        if (e.deviceAccounts() > 0) {
            if (deviceAccountsAt == null || Duration.between(deviceAccountsAt, e.at()).toDays() > 30 || e.deviceAccounts() >= deviceAccounts) {
                deviceAccounts = e.deviceAccounts();
                deviceAccountsAt = e.at();
            }
        }
        long left = e.amountMinor();
        while (left > 0 && !lots.isEmpty()) {
            Lot lot = lots.pollFirst();
            long used = Math.min(left, lot.remainingMinor());
            matches.addLast(new Match(lot.at(), e.at(), used));
            left -= used;
            if (used < lot.remainingMinor()) {
                lots.addFirst(new Lot(lot.at(), lot.remainingMinor() - used));
            }
        }
    }

    private void credit(AccountEvent e) {
        boolean newSender = e.counterparty() != null && !knownSenders.contains(e.counterparty());
        ins.addLast(new In(e.at(), e.amountMinor(), e.counterparty(), newSender, e.counterpartyRisk()));
        remember(knownSenders, e.counterparty());
        lots.addLast(new Lot(e.at(), e.amountMinor()));
        while (lots.size() > MAX_LOTS) {
            lots.pollFirst();
        }
    }

    private void evict(Instant now) {
        Instant day = now.minus(WINDOW);
        while (!outs.isEmpty() && outs.peekFirst().at().isBefore(day) || outs.size() > MAX_EVENTS) {
            outs.pollFirst();
        }
        while (!ins.isEmpty() && ins.peekFirst().at().isBefore(day) || ins.size() > MAX_EVENTS) {
            ins.pollFirst();
        }
        while (!matches.isEmpty() && matches.peekFirst().debitAt().isBefore(day) || matches.size() > MAX_EVENTS) {
            matches.pollFirst();
        }
        while (!nights.isEmpty() && nights.peekFirst().isBefore(day) || nights.size() > MAX_EVENTS) {
            nights.pollFirst();
        }
        while (!lots.isEmpty() && lots.peekFirst().at().isBefore(now.minus(LOT_TTL))) {
            lots.pollFirst();
        }
        while (!reports.isEmpty() && reports.peekFirst().isBefore(now.minus(REPORT_WINDOW))) {
            reports.pollFirst();
        }
    }

    private static void remember(LinkedHashSet<String> set, String key) {
        if (key == null) {
            return;
        }
        set.remove(key);
        set.add(key);
        if (set.size() > MAX_KNOWN) {
            Iterator<String> it = set.iterator();
            it.next();
            it.remove();
        }
    }

    public void setMuleScore(double score) {
        this.muleScore = score;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    // ------------------------------------------------------------------ reads

    public String account() {
        return account;
    }

    public Instant openedAt() {
        return openedAt;
    }

    public double muleScore() {
        return muleScore;
    }

    public Status status() {
        return status;
    }

    public boolean onHold() {
        return status == Status.ON_HOLD || status == Status.CONFIRMED_MULE;
    }

    public boolean knowsPayee(String payee) {
        return knownPayees.contains(payee);
    }

    public PayerSnapshot payerSnapshot(Instant now, Money availableBalance, String payee, boolean payeeTrusted, boolean killSwitch) {
        String currency = availableBalance.currency();
        int count1h = 0;
        long amount1h = 0;
        int count24h = 0;
        Set<String> newPayees = new HashSet<>();
        Instant hourAgo = now.minus(HOUR);
        Instant dayAgo = now.minus(WINDOW);
        for (Out o : outs) {
            if (o.at().isBefore(dayAgo) || o.at().isAfter(now)) {
                continue;
            }
            count24h++;
            if (o.newPayee()) {
                newPayees.add(o.payee());
            }
            if (!o.at().isBefore(hourAgo)) {
                count1h++;
                amount1h += o.amountMinor();
            }
        }
        return new PayerSnapshot(openedAt, availableBalance, count1h, new Money(amount1h, currency), count24h, newPayees.size(),
                logMean, Math.sqrt(logVar), history, knowsPayee(payee), payeeTrusted, killSwitch);
    }

    public PayeeSnapshot payeeSnapshot(Instant now, double intelScore) {
        Set<String> senders = new HashSet<>();
        int count = 0;
        for (In i : ins) {
            if (!i.at().isBefore(now.minus(WINDOW)) && !i.at().isAfter(now)) {
                count++;
                senders.add(i.sender());
            }
        }
        return new PayeeSnapshot(openedAt, muleScore, onHold(), count, senders.size(), reports30d(now), intelScore);
    }

    public int reports30d(Instant now) {
        return (int) reports.stream().filter(r -> !r.isBefore(now.minus(REPORT_WINDOW))).count();
    }

    /** Flows in the last 24 hours, for the mule features. */
    public Flows flows(Instant now, PolicyPack pack) {
        Instant dayAgo = now.minus(WINDOW);
        long in = 0;
        long newSenderIn = 0;
        double riskWeighted = 0;
        Set<String> senders = new HashSet<>();
        int inCount = 0;
        for (In i : ins) {
            if (i.at().isBefore(dayAgo)) {
                continue;
            }
            inCount++;
            in += i.amountMinor();
            senders.add(i.sender());
            if (i.newSender()) {
                newSenderIn += i.amountMinor();
            }
            riskWeighted += i.senderRisk() * i.amountMinor();
        }
        long out = 0;
        long cashOut = 0;
        Set<String> beneficiaries = new HashSet<>();
        int outCount = 0;
        for (Out o : outs) {
            if (o.at().isBefore(dayAgo)) {
                continue;
            }
            outCount++;
            out += o.amountMinor();
            if (o.type() == PaymentType.CASH_OUT) {
                cashOut += o.amountMinor();
            } else if (o.payee() != null) {
                beneficiaries.add(o.payee());
            }
        }
        long fast = 0;
        List<Match> recent = new ArrayList<>();
        for (Match m : matches) {
            if (m.creditAt().isBefore(dayAgo)) {
                continue;
            }
            recent.add(m);
            if (Duration.between(m.creditAt(), m.debitAt()).compareTo(HOUR) <= 0) {
                fast += m.amountMinor();
            }
        }
        double median = weightedMedianDwellMinutes(recent);
        long nightCount = nights.stream().filter(t -> !t.isBefore(dayAgo)).count();
        double scale = minorPerMajor(pack) * pack.amountScale().doubleValue();
        return new Flows(inCount, senders.size(), in / scale, in == 0 ? 0 : (double) newSenderIn / in,
                out / scale, beneficiaries.size(), out == 0 ? 0 : (double) cashOut / out,
                in == 0 ? Double.NaN : (double) fast / in, median,
                in == 0 ? 0 : riskWeighted / in,
                inCount + outCount == 0 ? 0 : (double) nightCount / (inCount + outCount));
    }

    /**
     * @param inScaled / outScaled value in and out in units of the pack's amount scale
     * @param fastShare            share of inbound value that left within an hour (NaN with no inbound)
     * @param medianDwellMinutes   value-weighted median time inbound money stayed (NaN when none left)
     * @param upstreamRisk         value-weighted mule score of the senders
     */
    public record Flows(int inCount, int distinctSenders, double inScaled, double newSenderShare,
                        double outScaled, int distinctBeneficiaries, double cashOutShare,
                        double fastShare, double medianDwellMinutes, double upstreamRisk, double nightShare) {
    }

    public int deviceAccounts() {
        return deviceAccounts;
    }

    /**
     * How long the account had been silent before its current spell of activity, if that spell began
     * in the last week (a dormant account that has just woken up); 0 otherwise.
     */
    public double dormantGapDays(Instant now) {
        return burstStartAt != null && Duration.between(burstStartAt, now).compareTo(REACTIVATION_WINDOW) <= 0 ? burstGapDays : 0;
    }

    public Instant firstSeenAt() {
        return firstSeenAt;
    }

    private static double weightedMedianDwellMinutes(List<Match> matches) {
        if (matches.isEmpty()) {
            return Double.NaN;
        }
        List<Match> sorted = new ArrayList<>(matches);
        sorted.sort(Comparator.comparing(m -> Duration.between(m.creditAt(), m.debitAt())));
        long total = sorted.stream().mapToLong(Match::amountMinor).sum();
        long seen = 0;
        for (Match m : sorted) {
            seen += m.amountMinor();
            if (seen * 2 >= total) {
                return Duration.between(m.creditAt(), m.debitAt()).toSeconds() / 60.0;
            }
        }
        return Double.NaN;
    }

    private static boolean isNight(Instant at, ZoneId zone) {
        int hour = at.atZone(zone).getHour();
        return hour >= 23 || hour < 6;
    }

    private static double minorPerMajor(PolicyPack pack) {
        return Math.pow(10, java.util.Currency.getInstance(pack.currency()).getDefaultFractionDigits());
    }
}
