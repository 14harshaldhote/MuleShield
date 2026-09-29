package io.muleshield.risk.store;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import io.muleshield.core.state.AccountState;
import tools.jackson.databind.json.JsonMapper;

/**
 * The online feature store: each account's state as the stream processor last wrote it, device
 * usage, shared intelligence and scam reports, all in Valkey. A payment decision reads everything
 * it needs with one Lua script, so the store costs one network round trip per payment.
 * <p>
 * Key layout, prefix {@code ms:}. On a Valkey cluster the keys of one lookup must share a slot;
 * production would hash-tag them by payer or split the payee reads into a second call.
 */
@Component
public class FeatureStore {

    static final Duration DEVICE_WINDOW = Duration.ofDays(30);
    static final Duration REPORT_WINDOW = Duration.ofDays(30);

    private final StringRedisTemplate redis;
    private final JsonMapper json;
    @SuppressWarnings("rawtypes")
    private final RedisScript<List> lookup = RedisScript.of(new ClassPathResource("lookup.lua"), List.class);

    public FeatureStore(StringRedisTemplate redis, JsonMapper json) {
        this.redis = redis;
        this.json = json;
    }

    public record Lookup(AccountState payer, AccountState payee, int deviceAccounts, double intel, int payeeReports) {
    }

    public Lookup lookup(String payer, String payee, String deviceKey, List<String> intelTokens, Instant now) {
        List<String> keys = List.of(state(payer), state(payee), "ms:dev:" + deviceKey,
                "ms:intel:" + intelTokens.get(0), "ms:intel:" + intelTokens.get(1), "ms:reports:" + payee);
        List<?> r = redis.execute(lookup, keys, payer, String.valueOf(now.toEpochMilli()),
                String.valueOf(now.minus(DEVICE_WINDOW).toEpochMilli()), String.valueOf(now.minus(REPORT_WINDOW).toEpochMilli()),
                String.valueOf(DEVICE_WINDOW.plusDays(1).toSeconds()));
        double intel = Math.max(parse(r.get(3)), parse(r.get(4)));
        return new Lookup(read(r.get(0)), read(r.get(1)), ((Number) r.get(2)).intValue(), intel, ((Number) r.get(5)).intValue());
    }

    public AccountState get(String account) {
        return read(redis.opsForValue().get(state(account)));
    }

    public void save(AccountState state) {
        redis.opsForValue().set(state(state.account()), json.writeValueAsString(state), Duration.ofDays(400));
    }

    public void addReport(String account, Instant at, String reportId) {
        String key = "ms:reports:" + account;
        redis.opsForZSet().add(key, reportId, at.toEpochMilli());
        redis.opsForZSet().removeRangeByScore(key, Double.NEGATIVE_INFINITY, at.minus(REPORT_WINDOW).toEpochMilli() - 1);
        redis.expire(key, REPORT_WINDOW.plusDays(1));
    }

    /** Keeps the strongest signal about a token; a CLEARED signal (weight 0) deletes it. */
    public void putIntel(String token, double weight, Duration ttl) {
        String key = "ms:intel:" + token;
        if (weight <= 0) {
            redis.delete(key);
            return;
        }
        String current = redis.opsForValue().get(key);
        if (current == null || Double.parseDouble(current) < weight) {
            redis.opsForValue().set(key, Double.toString(weight), ttl);
        }
    }

    private static String state(String account) {
        return "ms:acct:" + account;
    }

    private AccountState read(Object value) {
        return value == null ? null : json.readValue(value.toString(), AccountState.class);
    }

    private static double parse(Object value) {
        return value == null ? 0 : Double.parseDouble(value.toString());
    }
}
