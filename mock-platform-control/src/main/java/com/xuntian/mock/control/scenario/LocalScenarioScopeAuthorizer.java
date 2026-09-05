package com.xuntian.mock.control.scenario;

import com.xuntian.mock.control.identity.OperatorContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@Profile({"local", "test"})
public final class LocalScenarioScopeAuthorizer implements ScenarioScopeAuthorizer {

    @Override
    public void requireAllowed(OperatorContext operator, Set<String> tenants, Set<String> testAccounts) {
        // Local/test identities are isolated fixtures. Production must provide an ACL-backed adapter.
    }
}
