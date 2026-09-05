package com.xuntian.mock.control.flow;

public record FlowInstanceFilter(
        String environment,
        String appCode,
        String providerCode,
        String flowCode,
        String status) { }
