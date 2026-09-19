package com.movielife.service;

import com.movielife.ai.AiSummaryClientException;
import com.movielife.ai.AiSummaryFailureCode;
import com.movielife.ai.AiSummaryPublication;
import com.movielife.ai.ReviewSummaryGenerationResult;
import com.movielife.ai.ReviewSummarySnapshot;
import com.movielife.config.AiReviewSummaryProperties;
import com.movielife.entity.AiReviewSummary;
import com.movielife.entity.AiSummaryTask;
import com.movielife.enums.AiSummaryTargetType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MovieReviewAiSummaryWorkerTest {

    @Mock
    private AiSummaryPersistenceService persistenceService;
    @Mock
    private ReviewSummaryGenerationService generationService;
    @Mock
    private AiReviewSummaryCacheRepository cacheRepository;

    private AiReviewSummaryProperties properties;
    private MovieReviewAiSummaryWorker worker;

    @BeforeEach
    void setUp() {
        properties = new AiReviewSummaryProperties();
        properties.setEnabled(true);
        worker = new MovieReviewAiSummaryWorker(
                properties, persistenceService, generationService,
                new AiSummaryRetryPolicy(properties), cacheRepository);
    }

    @Test
    void shouldPublishGeneratedSummaryThenCachePersistedResult() {
        AiSummaryTask task = task(1, 4L);
        when(persistenceService.claim(eq(AiSummaryTargetType.MOVIE), eq(1L), any(), any(), any()))
                .thenReturn(Optional.of(task));
        AiSummaryPublication publication = new AiSummaryPublication();
        ReviewSummaryGenerationResult generated = ReviewSummaryGenerationResult.generated(
                snapshot(3), publication);
        when(generationService.generate(AiSummaryTargetType.MOVIE, 1L, 4L)).thenReturn(generated);
        AiReviewSummary persisted = new AiReviewSummary();
        persisted.setId(10L);
        when(persistenceService.publish(eq(publication), any(), any())).thenReturn(persisted);

        assertThat(worker.process(1L)).isTrue();

        verify(cacheRepository).put(persisted);
        verify(persistenceService, never()).markRetry(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldMarkInsufficientAndRemoveOldCache() {
        AiSummaryTask task = task(1, 4L);
        when(persistenceService.claim(eq(AiSummaryTargetType.MOVIE), eq(1L), any(), any(), any()))
                .thenReturn(Optional.of(task));
        when(generationService.generate(AiSummaryTargetType.MOVIE, 1L, 4L))
                .thenReturn(ReviewSummaryGenerationResult.insufficient(snapshot(2)));
        when(persistenceService.markInsufficient(eq(AiSummaryTargetType.MOVIE), eq(1L),
                eq(4L), any(), any())).thenReturn(true);

        assertThat(worker.process(1L)).isTrue();

        verify(cacheRepository).delete(AiSummaryTargetType.MOVIE, 1L);
        verify(persistenceService, never()).publish(any(), any(), any());
    }

    @Test
    void retryableFailureShouldEnterRetryWaitBeforeAttemptLimit() {
        AiSummaryTask task = task(1, 4L);
        when(persistenceService.claim(eq(AiSummaryTargetType.MOVIE), eq(1L), any(), any(), any()))
                .thenReturn(Optional.of(task));
        when(generationService.generate(AiSummaryTargetType.MOVIE, 1L, 4L))
                .thenThrow(new AiSummaryClientException(AiSummaryFailureCode.AI_TIMEOUT));
        when(persistenceService.markRetry(eq(AiSummaryTargetType.MOVIE), eq(1L), eq(4L),
                any(), eq("AI_TIMEOUT"), any(), any())).thenReturn(true);

        assertThat(worker.process(1L)).isTrue();

        verify(persistenceService).markRetry(eq(AiSummaryTargetType.MOVIE), eq(1L), eq(4L),
                any(), eq("AI_TIMEOUT"), any(), any());
    }

    @Test
    void finalAttemptAndAuthenticationFailureShouldBecomeDead() {
        AiSummaryTask finalAttempt = task(3, 4L);
        when(persistenceService.claim(eq(AiSummaryTargetType.MOVIE), eq(1L), any(), any(), any()))
                .thenReturn(Optional.of(finalAttempt));
        when(generationService.generate(AiSummaryTargetType.MOVIE, 1L, 4L))
                .thenThrow(new AiSummaryClientException(AiSummaryFailureCode.AI_TIMEOUT));
        when(persistenceService.markDead(eq(AiSummaryTargetType.MOVIE), eq(1L), eq(4L),
                any(), eq("AI_TIMEOUT"), any())).thenReturn(true);

        assertThat(worker.process(1L)).isTrue();

        verify(persistenceService).markDead(eq(AiSummaryTargetType.MOVIE), eq(1L), eq(4L),
                any(), eq("AI_TIMEOUT"), any());
    }

    @Test
    void duplicateMessageShouldAckWhenTaskCannotBeClaimed() {
        when(persistenceService.claim(eq(AiSummaryTargetType.MOVIE), eq(1L), any(), any(), any()))
                .thenReturn(Optional.empty());

        assertThat(worker.process(1L)).isTrue();

        verifyNoGeneration();
    }

    private void verifyNoGeneration() {
        verify(generationService, never()).generate(any(), any(), any());
    }

    private AiSummaryTask task(int attemptCount, Long processingVersion) {
        AiSummaryTask task = new AiSummaryTask();
        task.setAttemptCount(attemptCount);
        task.setProcessingVersion(processingVersion);
        return task;
    }

    private ReviewSummarySnapshot snapshot(int count) {
        return new ReviewSummarySnapshot(
                AiSummaryTargetType.MOVIE, 1L, count, Collections.emptyList());
    }
}
