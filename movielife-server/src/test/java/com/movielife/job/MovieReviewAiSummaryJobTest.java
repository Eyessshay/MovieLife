package com.movielife.job;

import com.movielife.config.AiReviewSummaryProperties;
import com.movielife.enums.AiSummaryTargetType;
import com.movielife.mapper.AiSummaryTaskMapper;
import com.movielife.service.AiSummaryStreamPublisher;
import com.movielife.service.MovieReviewAiSummaryWorker;
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
class MovieReviewAiSummaryJobTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private AiSummaryStreamPublisher streamPublisher;
    @Mock
    private MovieReviewAiSummaryWorker worker;
    @Mock
    private AiSummaryTaskMapper taskMapper;

    private AiReviewSummaryProperties properties;
    private MovieReviewAiSummaryJob job;

    @BeforeEach
    void setUp() {
        properties = new AiReviewSummaryProperties();
        job = new MovieReviewAiSummaryJob(
                properties, redisTemplate, streamPublisher, worker, taskMapper);
    }

    @Test
    void disabledFeatureShouldNotScanRetryTasks() {
        job.notifyReadyRetries();

        verifyNoInteractions(taskMapper, streamPublisher);
    }

    @Test
    void dueMovieRetriesShouldBeRepublishedToDedicatedStream() {
        properties.setEnabled(true);
        when(taskMapper.selectRetryReadyMovieIds(any(LocalDateTime.class), eq(100)))
                .thenReturn(Arrays.asList(11L, 12L));

        job.notifyReadyRetries();

        verify(streamPublisher).publish(AiSummaryTargetType.MOVIE, 11L);
        verify(streamPublisher).publish(AiSummaryTargetType.MOVIE, 12L);
    }
}
