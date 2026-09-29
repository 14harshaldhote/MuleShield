package io.muleshield.sim;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.Test;

class WorldTest {

    private static SimConfig config(Strategy s) {
        return new SimConfig("IN-RBI-2026", 5, 3000, 30, LocalDate.of(2026, 6, 1), s, 5, true, 8,
                Assumptions.central(), null, null, null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyStrategyFacesTheSameScamsAndMulesAreMeasured() {
        Map<String, Object> none = new World(config(Strategy.NONE)).run().summary(config(Strategy.NONE));
        Map<String, Object> rules = new World(config(Strategy.MULESHIELD_RULES)).run().summary(config(Strategy.MULESHIELD_RULES));
        var scamsNone = (Map<String, Object>) none.get("scams");
        var scamsRules = (Map<String, Object>) rules.get("scams");
        assertThat(scamsRules.get("cases")).isEqualTo(scamsNone.get("cases"));
        System.out.println(none.get("mules"));
        System.out.println(rules.get("mules"));
        var mules = (Map<String, Object>) none.get("mules");
        assertThat((Integer) mules.get("muleAccounts")).isGreaterThan(3);
        assertThat((Double) mules.get("heldShare")).isLessThanOrEqualTo(1.0);
    }
}
