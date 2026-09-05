package com.xuntian.mock.control.scenario;

import com.xuntian.mock.control.identity.OperatorContext;

import java.util.Set;

public interface ScenarioScopeAuthorizer {

    void requireAllowed(OperatorContext operator, Set<String> tenants, Set<String> testAccounts);
}
