package com.xuntian.mock.client.core.routing;

import com.xuntian.mock.client.core.context.MockContext;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class CanaryRule {

    private final Set<String> apps;
    private final Set<String> tenants;
    private final Set<String> testAccounts;

    private CanaryRule(Builder builder) {
        this.apps = immutableCopy(builder.apps);
        this.tenants = immutableCopy(builder.tenants);
        this.testAccounts = immutableCopy(builder.testAccounts);
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean matches(MockContext context) {
        boolean constrained = !apps.isEmpty() || !tenants.isEmpty() || !testAccounts.isEmpty();
        return constrained
                && (apps.isEmpty() || apps.contains(context.appCode()))
                && (tenants.isEmpty() || presentAndContains(tenants, context.tenant()))
                && (testAccounts.isEmpty() || presentAndContains(testAccounts, context.testAccount()));
    }

    private boolean presentAndContains(Set<String> values, String candidate) {
        return candidate != null && values.contains(candidate);
    }

    private static Set<String> immutableCopy(Set<String> values) {
        return Collections.unmodifiableSet(new HashSet<String>(values));
    }

    public static final class Builder {
        private final Set<String> apps = new HashSet<String>();
        private final Set<String> tenants = new HashSet<String>();
        private final Set<String> testAccounts = new HashSet<String>();

        private Builder() {
        }

        public Builder app(String app) {
            apps.add(app);
            return this;
        }

        public Builder tenant(String tenant) {
            tenants.add(tenant);
            return this;
        }

        public Builder testAccount(String testAccount) {
            testAccounts.add(testAccount);
            return this;
        }

        public CanaryRule build() {
            return new CanaryRule(this);
        }
    }
}
