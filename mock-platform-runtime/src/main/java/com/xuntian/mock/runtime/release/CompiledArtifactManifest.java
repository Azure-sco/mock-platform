package com.xuntian.mock.runtime.release;

public record CompiledArtifactManifest(
        String contractCompilerVersion,
        String matcherCompilerVersion,
        String templateCompilerVersion,
        String flowCompilerVersion) {

    public static final String CONTRACT_V1 = "contract-v1";
    public static final String MATCHER_V1 = "matcher-v1";
    public static final String TEMPLATE_V1 = "template-v1";
    public static final String FLOW_V1 = "flow-v1";

    public CompiledArtifactManifest(
            String contractCompilerVersion,
            String matcherCompilerVersion,
            String templateCompilerVersion) {
        this(contractCompilerVersion, matcherCompilerVersion, templateCompilerVersion, null);
    }

    public void requireSupported(boolean flowRequired) {
        if (!CONTRACT_V1.equals(contractCompilerVersion)
                || !MATCHER_V1.equals(matcherCompilerVersion)
                || !TEMPLATE_V1.equals(templateCompilerVersion)
                || (flowRequired && !FLOW_V1.equals(flowCompilerVersion))
                || (!flowRequired && flowCompilerVersion != null)) {
            throw new IllegalArgumentException("Snapshot compiled artifact versions are unsupported");
        }
    }
}
