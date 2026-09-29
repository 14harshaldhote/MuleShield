package io.muleshield.core.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * A tamper-evident audit log: every entry's hash covers the previous entry's hash, its own sequence
 * number and its payload. Editing, deleting or reordering any past entry breaks every hash after it,
 * so a debit hold, a release or an analyst's decision can't be quietly rewritten.
 * <p>
 * Regulators ask for exactly this trail on account freezes (RBI's draft SOP requires recording the
 * grounds and every step), and it is what an ombudsman reviews in a UK reimbursement dispute.
 *                                                         [RBI-HOLD-SOP] [OWASP A09:2025 Logging]
 */
public final class HashChain {

    public static final String GENESIS = "0".repeat(64);

    private HashChain() {
    }

    public record Link(long seq, String prevHash, String payload, String hash) {
        public Link {
            Objects.requireNonNull(prevHash, "prevHash");
            Objects.requireNonNull(payload, "payload");
            Objects.requireNonNull(hash, "hash");
        }
    }

    /** Appends {@code payload} after {@code previous} (null for the first entry). */
    public static Link append(Link previous, String payload) {
        long seq = previous == null ? 1 : previous.seq() + 1;
        String prev = previous == null ? GENESIS : previous.hash();
        return new Link(seq, prev, payload, hash(prev, seq, payload));
    }

    public static String hash(String prevHash, long seq, String payload) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(prevHash.getBytes(StandardCharsets.US_ASCII));
            sha.update((byte) '\n');
            sha.update(Long.toString(seq).getBytes(StandardCharsets.US_ASCII));
            sha.update((byte) '\n');
            sha.update(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The sequence number of the first entry that doesn't verify, or empty if the whole chain is intact. */
    public static OptionalLong firstBroken(List<Link> chain) {
        String prev = GENESIS;
        long expectedSeq = 1;
        for (Link link : chain) {
            if (link.seq() != expectedSeq || !link.prevHash().equals(prev)
                    || !MessageDigest.isEqual(link.hash().getBytes(StandardCharsets.US_ASCII),
                    hash(link.prevHash(), link.seq(), link.payload()).getBytes(StandardCharsets.US_ASCII))) {
                return OptionalLong.of(link.seq());
            }
            prev = link.hash();
            expectedSeq++;
        }
        return OptionalLong.empty();
    }
}
