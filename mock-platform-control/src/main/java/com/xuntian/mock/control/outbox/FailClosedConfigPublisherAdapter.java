package com.xuntian.mock.control.outbox;

import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!local & !test")
public final class FailClosedConfigPublisherAdapter implements ConfigPublisherAdapter {

    @Override
    public void publish(
            String targetType,
            String targetNamespace,
            String aggregateId,
            byte[] canonicalPayload,
            String checksum) {
        throw new PlatformException(ErrorCode.INTERNAL_ERROR, "Production Apollo/Nacos adapter is not configured");
    }
}
