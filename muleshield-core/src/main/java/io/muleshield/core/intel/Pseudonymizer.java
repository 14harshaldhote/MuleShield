package io.muleshield.core.intel;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import io.muleshield.core.payment.AccountRef;

/**
 * Turns an account into a token that banks in a fraud-sharing consortium can compare without
 * exchanging account numbers: HMAC-SHA256 under a key only members hold, salted with the month.
 * Two banks that see the same mule account compute the same token; anyone without the key learns
 * nothing, and tokens from different months can't be linked.
 * <p>
 * This is the data-minimising shape the UK's Economic Crime and Corporate Transparency Act 2023
 * (sharing to prevent economic crime), Australia's Scams Prevention Framework (reporting to the
 * regulator and sharing actionable intelligence) and India's I4C–RBIH mule-data exchange point
 * towards. Its limit, stated plainly: account numbers are guessable, so a member holding the key
 * can test a guess. Production keeps the key in an HSM behind a rate-limited lookup, or replaces
 * this with private set intersection.                      [AU-SPF] [UK-ECCTA-2023] [DPDP-2023]
 */
public final class Pseudonymizer {

    private final SecretKeySpec key;

    public Pseudonymizer(byte[] consortiumKey) {
        if (consortiumKey == null || consortiumKey.length < 32) {
            throw new IllegalArgumentException("The consortium key must be at least 256 bits");
        }
        this.key = new SecretKeySpec(consortiumKey.clone(), "HmacSHA256");
    }

    /** The month an observation belongs to; tokens rotate with it. */
    public static String epoch(Instant at) {
        return YearMonth.from(at.atOffset(ZoneOffset.UTC)).toString();
    }

    public String token(AccountRef account, String epoch) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            byte[] digest = mac.doFinal(("v1|" + epoch + "|" + account.key()).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Tokens to look up at {@code at}: this month's and last month's, so a signal doesn't vanish at midnight on the 1st. */
    public List<String> lookupTokens(AccountRef account, Instant at) {
        YearMonth month = YearMonth.from(at.atOffset(ZoneOffset.UTC));
        return List.of(token(account, month.toString()), token(account, month.minusMonths(1).toString()));
    }
}
