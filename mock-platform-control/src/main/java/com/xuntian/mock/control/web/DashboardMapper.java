package com.xuntian.mock.control.web;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DashboardMapper {

    @Select("""
            WITH request_metrics AS (
              SELECT
                COALESCE(SUM(request_count), 0) AS requests,
                COALESCE(SUM(matched_count), 0) AS matched_requests,
                COALESCE(SUM(no_match_count), 0) AS no_match_requests,
                COALESCE(SUM(latency_0_5_count), 0) AS b5,
                COALESCE(SUM(latency_6_10_count), 0) AS b10,
                COALESCE(SUM(latency_11_25_count), 0) AS b25,
                COALESCE(SUM(latency_26_50_count), 0) AS b50,
                COALESCE(SUM(latency_51_100_count), 0) AS b100,
                COALESCE(SUM(latency_101_250_count), 0) AS b250,
                COALESCE(SUM(latency_251_500_count), 0) AS b500,
                COALESCE(SUM(latency_501_1000_count), 0) AS b1000,
                COALESCE(SUM(latency_1001_3000_count), 0) AS b3000,
                COALESCE(MAX(max_duration_ms), 0) AS max_duration_ms
              FROM mock_request_metric_minute
              WHERE bucket_start >= CURRENT_TIMESTAMP - INTERVAL 24 HOUR
            )
            SELECT
              (SELECT COUNT(*) FROM mock_provider WHERE status = 'ENABLED') AS providers,
              (SELECT COUNT(*) FROM mock_api WHERE status = 'ENABLED') AS apis,
              (SELECT COUNT(*) FROM mock_scenario_version WHERE status = 'PUBLISHED') AS scenarios,
              (SELECT COUNT(*) FROM mock_release WHERE status IN ('PUBLISHED','PARTIAL')) AS releases,
              request_metrics.requests AS requests,
              request_metrics.matched_requests AS matchedRequests,
              request_metrics.no_match_requests AS noMatchRequests,
              CASE
                WHEN request_metrics.requests = 0 THEN 0
                WHEN request_metrics.requests < 20 THEN request_metrics.max_duration_ms
                WHEN request_metrics.b5 >= CEIL(request_metrics.requests * 0.95) THEN 5
                WHEN request_metrics.b5 + request_metrics.b10 >= CEIL(request_metrics.requests * 0.95) THEN 10
                WHEN request_metrics.b5 + request_metrics.b10 + request_metrics.b25 >= CEIL(request_metrics.requests * 0.95) THEN 25
                WHEN request_metrics.b5 + request_metrics.b10 + request_metrics.b25 + request_metrics.b50 >= CEIL(request_metrics.requests * 0.95) THEN 50
                WHEN request_metrics.b5 + request_metrics.b10 + request_metrics.b25 + request_metrics.b50 + request_metrics.b100 >= CEIL(request_metrics.requests * 0.95) THEN 100
                WHEN request_metrics.b5 + request_metrics.b10 + request_metrics.b25 + request_metrics.b50 + request_metrics.b100 + request_metrics.b250 >= CEIL(request_metrics.requests * 0.95) THEN 250
                WHEN request_metrics.b5 + request_metrics.b10 + request_metrics.b25 + request_metrics.b50 + request_metrics.b100 + request_metrics.b250 + request_metrics.b500 >= CEIL(request_metrics.requests * 0.95) THEN 500
                WHEN request_metrics.b5 + request_metrics.b10 + request_metrics.b25 + request_metrics.b50 + request_metrics.b100 + request_metrics.b250 + request_metrics.b500 + request_metrics.b1000 >= CEIL(request_metrics.requests * 0.95) THEN 1000
                WHEN request_metrics.b5 + request_metrics.b10 + request_metrics.b25 + request_metrics.b50 + request_metrics.b100 + request_metrics.b250 + request_metrics.b500 + request_metrics.b1000 + request_metrics.b3000 >= CEIL(request_metrics.requests * 0.95) THEN 3000
                ELSE request_metrics.max_duration_ms
              END AS p95DurationMs,
              (SELECT COUNT(*) FROM mock_callback_task
                WHERE updated_at >= CURRENT_TIMESTAMP(6) - INTERVAL 24 HOUR) AS callbackTasks,
              (SELECT COUNT(*) FROM mock_callback_task
                WHERE updated_at >= CURRENT_TIMESTAMP(6) - INTERVAL 24 HOUR
                  AND status = 'SUCCESS') AS callbackSucceeded,
              (SELECT COALESCE(SUM(send_attempt_count + preparation_retry_count), 0)
                 FROM mock_callback_task
                WHERE updated_at >= CURRENT_TIMESTAMP(6) - INTERVAL 24 HOUR) AS callbackAttempts
            FROM request_metrics
            """)
    DashboardMetrics selectMetrics();

    record DashboardMetrics(
            long providers,
            long apis,
            long scenarios,
            long releases,
            long requests,
            long matchedRequests,
            long noMatchRequests,
            long p95DurationMs,
            long callbackTasks,
            long callbackSucceeded,
            long callbackAttempts) {
        public double hitRate() {
            return requests == 0 ? 0 : matchedRequests * 100.0 / requests;
        }

        public double callbackSuccessRate() {
            return callbackTasks == 0 ? 0 : callbackSucceeded * 100.0 / callbackTasks;
        }

        public long callbackRetries() {
            return Math.max(0, callbackAttempts - callbackTasks);
        }
    }

}
