package io.muleshield.risk.api;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.muleshield.core.mule.MuleAssessment;
import io.muleshield.core.mule.MuleEngine;
import io.muleshield.core.policy.PolicyPack;
import io.muleshield.core.risk.RiskEngine;
import io.muleshield.core.state.AccountState;
import io.muleshield.risk.store.FeatureStore;
import jakarta.validation.Valid;

@RestController
class RiskController {

    private final AssessmentService assessments;
    private final FeatureStore store;
    private final MuleEngine muleEngine;
    private final RiskEngine riskEngine;
    private final PolicyPack pack;
    private final Clock clock;

    RiskController(AssessmentService assessments, FeatureStore store, MuleEngine muleEngine, RiskEngine riskEngine,
                   PolicyPack pack, Clock clock) {
        this.assessments = assessments;
        this.store = store;
        this.muleEngine = muleEngine;
        this.riskEngine = riskEngine;
        this.pack = pack;
        this.clock = clock;
    }

    @PostMapping("/v1/assessments")
    AssessmentResponse assess(@Valid @RequestBody AssessmentRequest request) {
        return assessments.assess(request);
    }

    /** For analysts: the account's current mule score and why, recomputed from its live state. */
    @GetMapping("/v1/accounts/{account}/mule-risk")
    ResponseEntity<MuleAssessment> muleRisk(@PathVariable String account) {
        AccountState state = store.get(account);
        if (state == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(muleEngine.assess(state, clock.instant(), 0));
    }

    /** The rules this deployment enforces and the models it runs. */
    @GetMapping("/v1/configuration")
    Map<String, Object> configuration() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("policyPack", pack);
        out.put("models", List.of(Map.of("kind", "payment", "version", riskEngine.scorer().version()),
                Map.of("kind", "mule", "version", muleEngine.scorer().version())));
        return out;
    }
}
