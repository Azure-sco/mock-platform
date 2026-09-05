package com.xuntian.mock.control.requestlog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Profile("!test")
@ConditionalOnProperty(
        prefix = "mock.request-log.partition",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public final class RequestLogPartitionMaintenance {

    private static final Logger LOGGER = LoggerFactory.getLogger(RequestLogPartitionMaintenance.class);
    private static final Pattern DAILY_PARTITION = Pattern.compile("p(\\d{8})");
    private static final String LOCK_NAME = "mock_request_log_partition_maintenance";

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final int futureDays;
    private final int retentionDays;

    @Autowired
    public RequestLogPartitionMaintenance(
            JdbcTemplate jdbc,
            Clock clock,
            org.springframework.core.env.Environment environment) {
        this(
                jdbc,
                clock,
                integer(environment, "mock.request-log.partition.future-days", 31),
                integer(environment, "mock.request-log.partition.retention-days", 7));
    }

    public RequestLogPartitionMaintenance(JdbcTemplate jdbc, Clock clock, int futureDays, int retentionDays) {
        if (futureDays < 1 || futureDays > 366) {
            throw new IllegalArgumentException("Request Log future-days must be from 1 to 366");
        }
        if (retentionDays < 1 || retentionDays > 3650) {
            throw new IllegalArgumentException("Request Log retention-days must be from 1 to 3650");
        }
        this.jdbc = jdbc;
        this.clock = clock;
        this.futureDays = futureDays;
        this.retentionDays = retentionDays;
    }

    @Scheduled(
            initialDelayString = "${mock.request-log.partition.initial-delay-ms:60000}",
            fixedDelayString = "${mock.request-log.partition.fixed-delay-ms:21600000}")
    public void scheduledMaintenance() {
        try {
            MaintenanceResult result = maintainNow();
            if (result.addedPartitions() > 0 || result.droppedPartitions() > 0
                    || result.legacyTruncated() || result.deletedMetricBuckets() > 0) {
                LOGGER.info(
                        "Request Log storage maintained added={} dropped={} legacyTruncated={} metricBucketsDeleted={}",
                        result.addedPartitions(), result.droppedPartitions(), result.legacyTruncated(),
                        result.deletedMetricBuckets());
            }
        } catch (RuntimeException failure) {
            LOGGER.error("Request Log storage maintenance failed type={}",
                    failure.getClass().getName(), failure);
        }
    }

    public MaintenanceResult maintainNow() {
        Integer acquired = jdbc.queryForObject("SELECT GET_LOCK(?, 0)", Integer.class, LOCK_NAME);
        if (acquired == null || acquired != 1) return new MaintenanceResult(0, 0, false, 0, false);
        try {
            LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
            int added = ensureFuturePartitions(today.plusDays(futureDays));
            CleanupResult cleanup = dropExpiredPartitions(today.minusDays(retentionDays));
            int metricBuckets = jdbc.update(
                    "DELETE FROM mock_request_metric_minute WHERE bucket_start < ?",
                    java.sql.Timestamp.valueOf(today.minusDays(retentionDays).atStartOfDay()));
            return new MaintenanceResult(
                    added, cleanup.dropped(), cleanup.legacyTruncated(), metricBuckets, true);
        } finally {
            jdbc.queryForObject("SELECT RELEASE_LOCK(?)", Integer.class, LOCK_NAME);
        }
    }

    private int ensureFuturePartitions(LocalDate targetDay) {
        List<Partition> partitions = partitions();
        LocalDate lastDay = partitions.stream()
                .map(Partition::day)
                .filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElseThrow(() -> new IllegalStateException("Request Log daily partitions are missing"));
        if (!lastDay.isBefore(targetDay)) return 0;

        List<LocalDate> missing = new ArrayList<>();
        for (LocalDate day = lastDay.plusDays(1); !day.isAfter(targetDay); day = day.plusDays(1)) {
            missing.add(day);
        }
        StringBuilder ddl = new StringBuilder(
                "ALTER TABLE mock_request_log REORGANIZE PARTITION p_future INTO (");
        for (LocalDate day : missing) {
            ddl.append("PARTITION p")
                    .append(day.toString().replace("-", ""))
                    .append(" VALUES LESS THAN ('")
                    .append(day.plusDays(1))
                    .append("'),");
        }
        ddl.append("PARTITION p_future VALUES LESS THAN MAXVALUE)");
        jdbc.execute(ddl.toString());
        return missing.size();
    }

    private CleanupResult dropExpiredPartitions(LocalDate cutoff) {
        List<Partition> partitions = partitions();
        List<String> expired = partitions.stream()
                .filter(partition -> partition.day() != null && partition.day().isBefore(cutoff))
                .map(Partition::name)
                .toList();
        if (!expired.isEmpty()) {
            String names = String.join(",", expired.stream().map(this::identifier).toList());
            jdbc.execute("ALTER TABLE mock_request_log DROP PARTITION " + names);
        }

        boolean legacyTruncated = false;
        Partition legacy = partitions.stream()
                .filter(partition -> "p_legacy".equals(partition.name()))
                .findFirst()
                .orElse(null);
        if (legacy != null && legacy.boundaryDate() != null) {
            LocalDate legacyBefore = legacy.boundaryDate();
            if (!legacyBefore.isAfter(cutoff)) {
                List<Integer> rows = jdbc.query(
                        "SELECT 1 FROM mock_request_log PARTITION (p_legacy) LIMIT 1",
                        (result, row) -> result.getInt(1));
                if (!rows.isEmpty()) {
                    jdbc.execute("ALTER TABLE mock_request_log TRUNCATE PARTITION p_legacy");
                    legacyTruncated = true;
                }
            }
        }
        return new CleanupResult(expired.size(), legacyTruncated);
    }

    private List<Partition> partitions() {
        return jdbc.query("""
                SELECT partition_name, partition_description
                  FROM information_schema.partitions
                 WHERE table_schema = DATABASE()
                   AND table_name = 'mock_request_log'
                   AND partition_name IS NOT NULL
                """, (result, row) -> partition(
                result.getString("partition_name"), result.getString("partition_description")));
    }

    private Partition partition(String name, String description) {
        Matcher matcher = DAILY_PARTITION.matcher(name);
        LocalDate day = matcher.matches()
                ? LocalDate.parse(matcher.group(1), java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
                : null;
        LocalDate boundary = description == null || "MAXVALUE".equalsIgnoreCase(description)
                ? null
                : LocalDate.parse(description.replace("'", ""));
        return new Partition(name, day, boundary);
    }

    private String identifier(String value) {
        if (!DAILY_PARTITION.matcher(value).matches()) {
            throw new IllegalArgumentException("Unsafe partition identifier");
        }
        return "`" + value + "`";
    }

    private static int integer(org.springframework.core.env.Environment environment, String key, int fallback) {
        String value = environment.getProperty(key);
        try {
            return value == null ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(key + " must be an integer", invalid);
        }
    }

    private record Partition(String name, LocalDate day, LocalDate boundaryDate) {
    }

    private record CleanupResult(int dropped, boolean legacyTruncated) {
    }

    public record MaintenanceResult(
            int addedPartitions,
            int droppedPartitions,
            boolean legacyTruncated,
            int deletedMetricBuckets,
            boolean lockAcquired) {
    }
}
