package com.xuntian.mock.control.scenario;

import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import com.xuntian.mock.control.identity.OperatorContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@Profile("!local & !test")
public final class FailClosedScenarioScopeAuthorizer implements ScenarioScopeAuthorizer {

    @Override
    public void requireAllowed(OperatorContext operator, Set<String> tenants, Set<String> testAccounts) {
        throw new PlatformException(
                ErrorCode.FORBIDDEN,
                "Production tenant/test-account authorization adapter is not configured");
    }
}
