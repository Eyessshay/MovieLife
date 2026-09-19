package com.movielife.job;

import com.movielife.config.AiReviewSummaryProperties;
import com.movielife.entity.AiSummaryTask;
import com.movielife.enums.AiSummaryTargetType;
import com.movielife.mapper.AiSummaryTaskMapper;
import com.movielife.service.AiSummaryStreamPublisher;
import com.movielife.service.ReviewAiSummaryWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.Arrays;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewAiSummaryJobTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private AiSummaryStreamPublisher streamPublisher;
    @Mock
    private ReviewAiSummaryWorker worker;
    @Mock
    private AiSummaryTaskMapper taskMapper;

    private AiReviewSummaryProperties properties;
    private ReviewAiSummaryJob job;

    @BeforeEach
    void setUp() {
        properties = new AiReviewSummaryProperties();
        job = new ReviewAiSummaryJob(
                properties, redisTemplate, streamPublisher, worker, taskMapper);
    }

    @Test
    void disabledFeatureShouldNotScanRetryTasks() {
        job.notifyReadyRetries();

        verifyNoInteractions(taskMapper, streamPublisher);
    }

    @Test
    void dueMovieAndActivityRetriesShouldBeRepublishedToDedicatedStream() {
        properties.setEnabled(true);
        when(taskMapper.selectRetryReadyTargets(any(LocalDateTime.class), eq(100)))
                .thenReturn(Arrays.asList(target("MOVIE", 11L), target("ACTIVITY", 12L)));

        job.notifyReadyRetries();

        verify(streamPublisher).publish(AiSummaryTargetType.MOVIE, 11L);
        verify(streamPublisher).publish(AiSummaryTargetType.ACTIVITY, 12L);
    }

    private AiSummaryTask target(String type, Long id) {
        AiSummaryTask task = new AiSummaryTask();
        task.setTargetType(type);
        task.setTargetId(id);
        return task;
    }
}
