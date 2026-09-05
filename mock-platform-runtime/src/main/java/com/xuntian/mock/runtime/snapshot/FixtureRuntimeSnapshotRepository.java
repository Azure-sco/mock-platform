package com.xuntian.mock.runtime.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuntian.mock.runtime.RuntimeProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Component
@Profile({"local", "test"})
public final class FixtureRuntimeSnapshotRepository implements RuntimeSnapshotRepository {

    private final Map<SnapshotKey, RuntimeSnapshot> snapshots;

    public FixtureRuntimeSnapshotRepository(
            RuntimeProperties properties,
            ResourceLoader resourceLoader,
            ObjectMapper mapper,
            RuntimeSnapshotCompiler compiler) {
        Resource resource = resourceLoader.getResource(properties.getFixtureLocation());
        try (InputStream input = resource.getInputStream()) {
            FixtureDefinition definition = mapper.readValue(input, FixtureDefinition.class);
            Map<SnapshotKey, RuntimeSnapshot> loaded = new LinkedHashMap<>();
            for (RuntimeSnapshot snapshot : compiler.compile(definition)) {
                SnapshotKey key = new SnapshotKey(snapshot.environment(), snapshot.app());
                if (loaded.put(key, snapshot) != null) {
                    throw new IllegalArgumentException("Duplicate Fixture Snapshot: " + key);
                }
            }
            this.snapshots = Map.copyOf(loaded);
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException(
                    "Cannot load valid Runtime Fixture Snapshot from " + properties.getFixtureLocation(),
                    failure);
        }
    }

    @Override
    public Optional<RuntimeSnapshot> find(String environment, String app) {
        return Optional.ofNullable(snapshots.get(new SnapshotKey(environment, app)));
    }

    private record SnapshotKey(String environment, String app) { }
}
