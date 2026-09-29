package io.muleshield.core.intel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import io.muleshield.core.payment.AccountRef;

class PseudonymizerTest {

    private static final byte[] KEY = "consortium-key-for-tests-only-32bytes!".getBytes(StandardCharsets.UTF_8);

    @Test
    void twoBanksComputeTheSameTokenForTheSameAccount() {
        var bankA = new Pseudonymizer(KEY);
        var bankB = new Pseudonymizer(KEY.clone());
        var mule = new AccountRef("hdfc0001234", "50100012345678");
        assertThat(bankA.token(mule, "2026-09")).isEqualTo(bankB.token(new AccountRef("HDFC0001234", " 50100012345678 "), "2026-09"));
    }

    @Test
    void tokensRotateMonthlyAndRevealNothing() {
        var p = new Pseudonymizer(KEY);
        var account = new AccountRef("SBIN0000001", "30000000001");
        String sep = p.token(account, "2026-09");
        assertThat(p.token(account, "2026-10")).isNotEqualTo(sep);
        assertThat(sep).doesNotContain("30000000001").hasSize(43);
        assertThat(p.lookupTokens(account, Instant.parse("2026-10-01T00:05:00Z"))).containsExactly(p.token(account, "2026-10"), sep);
    }

    @Test
    void rejectsAWeakKey() {
        assertThatThrownBy(() -> new Pseudonymizer(new byte[16])).hasMessageContaining("256 bits");
    }
}
