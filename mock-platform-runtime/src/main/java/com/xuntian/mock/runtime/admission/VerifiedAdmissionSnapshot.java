package com.xuntian.mock.runtime.admission;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public record VerifiedAdmissionSnapshot(
        String bindingId,
        AdmissionScope scope,
        long policyVersionId,
        long bindingVersion,
        String checksum,
        String signatureKeyId,
        Instant issuedAt,
        Instant notAfter,
        List<Rule> rules) {

    public boolean allows(String providerCode, String apiCode, String tenantCode, String testAccount) {
        return rules.stream().anyMatch(rule -> rule.allows(providerCode, apiCode, tenantCode, testAccount));
    }

    public record Rule(
            String providerCode,
            Set<String> apiCodes,
            Set<String> tenantCodes,
            Set<String> testAccounts) {

        boolean allows(String provider, String api, String tenant, String account) {
            return providerCode.equals(provider)
                    && (apiCodes.isEmpty() || apiCodes.contains(api))
                    && (tenantCodes.isEmpty() || tenant != null && tenantCodes.contains(tenant))
                    && (testAccounts.isEmpty() || account != null && testAccounts.contains(account));
        }
    }
}
