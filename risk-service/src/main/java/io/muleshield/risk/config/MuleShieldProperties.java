package io.muleshield.risk.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

/**
 * @param bankId        this bank's identifier; only its own accounts get state and mule scoring
 * @param policyPack    the jurisdiction's rules, e.g. IN-RBI-2026 or UK-PSR-2024
 * @param consortiumKey base64 key shared by the banks exchanging mule signals (from a secret, never the repo)
 * @param serviceToken  bearer token the other services present (mTLS in production)
 */
@ConfigurationProperties("muleshield")
public record MuleShieldProperties(
        String bankId,
        String policyPack,
        Resource paymentModel,
        Resource muleModel,
        String consortiumKey,
        String serviceToken,
        Duration intelTtl) {
}
