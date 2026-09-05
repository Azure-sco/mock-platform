package com.xuntian.mock.control.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import com.xuntian.mock.control.identity.OperatorContext;
import org.springframework.stereotype.Service;

@Service
public final class AuditService {

    private final AuditMapper auditMapper;
    private final ObjectMapper objectMapper;

    public AuditService(AuditMapper auditMapper, ObjectMapper objectMapper) {
        this.auditMapper = auditMapper;
        this.objectMapper = objectMapper;
    }

    public void record(
            OperatorContext operator,
            String action,
            String objectType,
            Object objectId,
            String objectChecksum,
            Object before,
            Object after) {
        auditMapper.insert(
                operator.requestId(),
                operator.operatorId(),
                action,
                objectType,
                String.valueOf(objectId),
                objectChecksum,
                json(before),
                json(after));
    }

    private String json(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new PlatformException(ErrorCode.INTERNAL_ERROR, "Audit metadata cannot be serialized", failure);
        }
    }
}
