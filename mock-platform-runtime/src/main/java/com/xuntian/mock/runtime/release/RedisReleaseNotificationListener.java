package com.xuntian.mock.runtime.release;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.scheduler.Scheduler;

import java.time.Instant;

@Component
@Profile("!local & !test")
public final class RedisReleaseNotificationListener {

    private static final Logger LOG = LoggerFactory.getLogger(RedisReleaseNotificationListener.class);
    static final String CHANNEL = "mock:release-invalidate";
    private final ReactiveStringRedisTemplate redis;
    private final ReleaseRefreshCoordinator coordinator;
    private final Scheduler releaseRefreshScheduler;
    private Disposable subscription;

    public RedisReleaseNotificationListener(
            ReactiveStringRedisTemplate redis,
            ReleaseRefreshCoordinator coordinator,
            @Qualifier("runtimeReleaseRefreshScheduler") Scheduler runtimeReleaseRefreshScheduler) {
        this.redis = redis;
        this.coordinator = coordinator;
        this.releaseRefreshScheduler = runtimeReleaseRefreshScheduler;
    }

    @PostConstruct
    void subscribe() {
        subscription = redis.listenTo(ChannelTopic.of(CHANNEL))
                .publishOn(releaseRefreshScheduler)
                .subscribe(
                        ignored -> coordinator.refreshAll(Instant.now()),
                        failure -> LOG.warn("Runtime Release notifications stopped; polling remains active"));
    }

    @PreDestroy
    void unsubscribe() {
        if (subscription != null) {
            subscription.dispose();
        }
    }
}
