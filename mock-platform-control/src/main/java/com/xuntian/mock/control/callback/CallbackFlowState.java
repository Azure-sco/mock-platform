package com.xuntian.mock.control.callback;

public record CallbackFlowState(long id, int generation, String status) {

    public boolean accepts(int expectedGeneration) {
        return "ACTIVE".equals(status) && generation == expectedGeneration;
    }
}
