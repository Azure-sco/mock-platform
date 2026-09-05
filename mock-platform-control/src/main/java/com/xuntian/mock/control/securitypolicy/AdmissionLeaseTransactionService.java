package com.xuntian.mock.control.securitypolicy;

import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class AdmissionLeaseTransactionService {

    private final SecurityPolicyMapper mapper;

    public AdmissionLeaseTransactionService(SecurityPolicyMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public void markProjected(
            String bindingId,
            long bindingVersion,
            long policyVersionId,
            Instant now,
            String worker) {
        SecurityPolicyBindingRecord binding = mapper.lockBindingById(bindingId);
        if (binding == null || binding.bindingVersion() != bindingVersion
                || binding.desiredPolicyVersionId() != policyVersionId) {
            throw new PlatformException(ErrorCode.CONFLICT, "Admission Binding changed before lease finalize");
        }
        if ("BOUND".equals(binding.status())
                && binding.effectivePolicyVersionId() != null
                && binding.effectivePolicyVersionId() == policyVersionId) {
            return;
        }
        if (mapper.markAdmissionProjected(bindingId, bindingVersion, policyVersionId, now, worker) != 1) {
            throw new PlatformException(ErrorCode.CONFLICT, "Admission Binding changed before lease finalize");
        }
    }

    @Transactional
    public void markEffective(String bindingId, long bindingVersion, Instant now, String worker) {
        SecurityPolicyBindingRecord binding = mapper.lockBindingById(bindingId);
        if (binding == null || binding.bindingVersion() != bindingVersion
                || !"BOUND".equals(binding.status())
                || binding.effectivePolicyVersionId() == null
                || binding.effectivePolicyVersionId() != binding.desiredPolicyVersionId()) {
            throw new PlatformException(ErrorCode.CONFLICT, "Admission Binding changed before EFFECTIVE aggregation");
        }
        mapper.markAdmissionEffective(bindingId, bindingVersion, now, worker);
    }
}
