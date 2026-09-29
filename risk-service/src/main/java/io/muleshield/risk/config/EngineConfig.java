package io.muleshield.risk.config;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.util.Base64;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import io.muleshield.core.intel.Pseudonymizer;
import io.muleshield.core.ml.GbmModel;
import io.muleshield.core.mule.MuleEngine;
import io.muleshield.core.mule.MuleFeature;
import io.muleshield.core.policy.PolicyPack;
import io.muleshield.core.policy.PolicyPacks;
import io.muleshield.core.risk.PaymentFeature;
import io.muleshield.core.risk.RiskEngine;

/** The engines are plain objects from the framework-free core; Spring only wires them. */
@Configuration(proxyBeanMethods = false)
public class EngineConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    PolicyPack policyPack(MuleShieldProperties props) {
        return PolicyPacks.load(props.policyPack());
    }

    @Bean
    RiskEngine riskEngine(PolicyPack pack, MuleShieldProperties props) throws IOException {
        return new RiskEngine(pack, load(props.paymentModel(), true));
    }

    @Bean
    MuleEngine muleEngine(PolicyPack pack, MuleShieldProperties props) throws IOException {
        return new MuleEngine(pack, load(props.muleModel(), false));
    }

    @Bean
    Pseudonymizer pseudonymizer(MuleShieldProperties props) {
        return new Pseudonymizer(Base64.getDecoder().decode(props.consortiumKey()));
    }

    private static GbmModel load(Resource model, boolean payment) throws IOException {
        try (InputStream in = model.getInputStream()) {
            // Fails at startup if the model was trained on different features than this build computes.
            return GbmModel.load(in, payment ? PaymentFeature.NAMES : MuleFeature.NAMES);
        }
    }
}
