package com.xuntian.mock.runtime.engine;

import java.util.Set;

public record ScenarioScope(
        Set<String> environments,
        Set<String> apps,
        Set<String> tenants,
        Set<String> testAccounts) {

    public ScenarioScope {
        environments = immutable(environments);
        apps = immutable(apps);
        tenants = immutable(tenants);
        testAccounts = immutable(testAccounts);
    }

    public boolean matches(RuntimeRequest request) {
        return contains(environments, request.environment())
                && contains(apps, request.app())
                && contains(tenants, request.tenant())
                && contains(testAccounts, request.testAccount());
    }

    private static Set<String> immutable(Set<String> values) {
        return values == null ? Set.of() : Set.copyOf(values);
    }

    private static boolean contains(Set<String> expected, String actual) {
        return expected.isEmpty() || actual != null && expected.contains(actual);
    }
}
