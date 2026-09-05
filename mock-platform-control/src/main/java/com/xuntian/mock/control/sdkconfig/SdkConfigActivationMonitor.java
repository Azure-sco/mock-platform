package com.xuntian.mock.control.sdkconfig;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public final class SdkConfigActivationMonitor {

    private final SdkConfigService service;

    public SdkConfigActivationMonitor(SdkConfigService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${mock.sdk-config.activation-monitor.fixed-delay-ms:1000}")
    public void reconcile() {
        service.reconcileTargets();
    }
}
