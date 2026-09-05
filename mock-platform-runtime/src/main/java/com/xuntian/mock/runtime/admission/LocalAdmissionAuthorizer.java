package com.xuntian.mock.runtime.admission;

import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import com.xuntian.mock.runtime.RuntimeProperties;
import com.xuntian.mock.runtime.identity.RuntimeIdentity;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;
import java.util.regex.Pattern;

@Component
@Profile({"local", "test"})
public final class LocalAdmissionAuthorizer implements AdmissionAuthorizer {

    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private final String environment;
    private final Set<String> admittedApps;

    public LocalAdmissionAuthorizer(RuntimeProperties properties) {
        this.environment = properties.getEnvironment();
        this.admittedApps = Set.copyOf(properties.getLocalAppTokens().values());
    }

    @Override
    public void authorize(RuntimeIdentity identity, String providerCode, String apiCode, Instant now) {
        if (!environment.equals(identity.environment())
                || !admittedApps.contains(identity.appCode())
                || providerCode == null || !CODE.matcher(providerCode).matches()
                || apiCode == null || !CODE.matcher(apiCode).matches()) {
            throw new PlatformException(
                    ErrorCode.MOCK_FORBIDDEN,
                    "Mock request is denied by the local Admission configuration");
        }
    }
}
