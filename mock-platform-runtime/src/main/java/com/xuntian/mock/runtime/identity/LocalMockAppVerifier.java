package com.xuntian.mock.runtime.identity;

import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import com.xuntian.mock.runtime.RuntimeProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile({"local", "test"})
public final class LocalMockAppVerifier implements MockAppVerifier {

    private static final String PREFIX = "MockApp ";
    private final RuntimeProperties properties;

    public LocalMockAppVerifier(RuntimeProperties properties) {
        this.properties = properties;
    }

    @Override
    public RuntimeIdentity verify(HttpHeaders headers) {
        String authorization = headers.getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith(PREFIX)) {
            throw unauthorized();
        }
        String token = authorization.substring(PREFIX.length());
        String appCode = properties.getLocalAppTokens().get(token);
        if (appCode == null) {
            throw unauthorized();
        }
        return new RuntimeIdentity(appCode, properties.getEnvironment());
    }

    private PlatformException unauthorized() {
        return new PlatformException(ErrorCode.MOCK_APP_UNAUTHORIZED, "Mock application identity is invalid");
    }
}
