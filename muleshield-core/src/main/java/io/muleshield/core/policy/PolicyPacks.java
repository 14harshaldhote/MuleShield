package io.muleshield.core.policy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.dataformat.yaml.YAMLMapper;

/** Loads the policy packs bundled on the classpath. Unknown keys fail the load: a typo must not silently disable a rule. */
public final class PolicyPacks {

    public static final List<String> BUNDLED = List.of("IN-RBI-2026", "UK-PSR-2024", "EU-IPR-2025", "SG-SRF-2024", "AU-SPF-2025");

    private static final YAMLMapper YAML = YAMLMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    private PolicyPacks() {
    }

    public static PolicyPack load(String id) {
        String path = "/policy-packs/" + id + ".yaml";
        try (InputStream in = PolicyPacks.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalArgumentException("No policy pack " + id + " (expected one of " + BUNDLED + ")");
            }
            PolicyPack pack = YAML.readValue(in, PolicyPack.class);
            validate(pack, id);
            return pack;
        } catch (IOException e) {
            throw new UncheckedIOException("Can't read policy pack " + id, e);
        }
    }

    public static Map<String, PolicyPack> all() {
        Map<String, PolicyPack> packs = new LinkedHashMap<>();
        BUNDLED.forEach(id -> packs.put(id, load(id)));
        return packs;
    }

    private static void validate(PolicyPack pack, String id) {
        if (!id.equals(pack.id())) {
            throw new IllegalStateException("Pack file " + id + " declares id " + pack.id());
        }
        var t = pack.thresholds();
        if (!(0 < t.stepUp() && t.stepUp() < t.hold() && t.hold() < t.block() && t.block() <= 1)) {
            throw new IllegalStateException(id + ": thresholds must rise: stepUp < hold < block <= 1");
        }
        if (!(0 < t.muleFlag() && t.muleFlag() <= t.muleAutoHold() && t.muleAutoHold() <= 1)) {
            throw new IllegalStateException(id + ": mule thresholds must satisfy 0 < flag <= autoHold <= 1");
        }
        if (pack.references() == null || pack.references().isEmpty()) {
            throw new IllegalStateException(id + ": every pack must cite its legal references");
        }
        if (pack.amountScale() == null || pack.amountScale().signum() <= 0) {
            throw new IllegalStateException(id + ": amountScale must be positive");
        }
        Set<String> known = new HashSet<>();
        pack.references().forEach(r -> known.add(r.key()));
        known.add("NONE");
        for (String ref : List.of(pack.protectiveDelay().ref(), pack.riskHold().ref(), pack.payeeVerification().ref(),
                pack.debitHold().ref(), pack.creditCap().ref(), pack.newDeviceCooling().ref(), pack.drainProtection().ref(),
                pack.stepUp().ref(), pack.reimbursement().ref())) {
            if (!known.contains(ref)) {
                throw new IllegalStateException(id + ": rule cites " + ref + ", which is not in its references");
            }
        }
    }
}
