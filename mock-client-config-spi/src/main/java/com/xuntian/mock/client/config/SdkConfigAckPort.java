package com.xuntian.mock.client.config;

/** Outbound port for Control's idempotent SDK activation acknowledgement endpoint. */
public interface SdkConfigAckPort {

    void report(SdkConfigAck acknowledgement) throws Exception;
}
