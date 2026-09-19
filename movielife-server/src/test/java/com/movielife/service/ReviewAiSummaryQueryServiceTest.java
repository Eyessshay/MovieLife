package com.movielife.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.movielife.ai.AiReviewSummaryCacheValue;
import com.movielife.config.AiReviewSummaryProperties;
import com.movielife.entity.AiSummaryTask;
import com.movielife.entity.Activity;
import com.movielife.entity.Movie;
import com.movielife.enums.AiSummaryTargetType;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.mapper.ActivityMapper;
import com.movielife.mapper.MovieMapper;
import com.movielife.mapper.ReviewMapper;
import com.movielife.utils.JsonListUtils;
import com.movielife.vo.ReviewAiSummaryVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ReviewAiSummaryQueryServiceTest {

    @Mock
    private MovieMapper movieMapper;
    @Mock
    private ActivityMapper activityMapper;
    @Mock
    private ReviewMapper reviewMapper;
    @Mock
    private AiSummaryPersistenceService persistenceService;
    @Mock
    private AiReviewSummaryCacheRepository cacheRepository;

    private ReviewAiSummaryQueryService service;

    @BeforeEach
    void setUp() {
        ReviewSummaryTargetPolicyRegistry registry = new ReviewSummaryTargetPolicyRegistry(
                java.util.Arrays.asList(new MovieSummaryTargetPolicy(movieMapper),
                        new ActivitySummaryTargetPolicy(activityMapper)));
        service = new ReviewAiSummaryQueryService(
                reviewMapper, persistenceService, cacheRepository,
                new AiReviewSummaryProperties(), new JsonListUtils(new ObjectMapper()), registry);
    }

    @Test
    void shouldRejectUnsupportedTypeAndMissingMovie() {
        assertBusinessError(() -> service.query("VENUE", 1L), ErrorCode.INVALID_TARGET_TYPE);
        when(movieMapper.selectById(1L)).thenReturn(null);
        assertBusinessError(() -> service.query("MOVIE", 1L), ErrorCode.MOVIE_NOT_FOUND);
    }

    @Test
    void shouldReturnNotEnoughWithoutCreatingTask() {
        when(movieMapper.selectById(1L)).thenReturn(new Movie());
        when(reviewMapper.countActiveByTarget("MOVIE", 1L)).thenReturn(2);

        ReviewAiSummaryVO result = service.query("MOVIE", 1L);

        assertThat(result.getStatus()).isEqualTo("NOT_ENOUGH_REVIEWS");
        assertThat(result.getSourceReviewCount()).isEqualTo(2);
        assertThat(result.getSummary()).isNull();
    }

    @Test
    void activeActivityShouldUseSameNotEnoughContract() {
        Activity activity = new Activity();
        activity.setStatus(1);
        when(activityMapper.selectById(2L)).thenReturn(activity);
        when(reviewMapper.countActiveByTarget("ACTIVITY", 2L)).thenReturn(2);

        ReviewAiSummaryVO result = service.query("ACTIVITY", 2L);

        assertThat(result.getTargetType()).isEqualTo("ACTIVITY");
        assertThat(result.getStatus()).isEqualTo("NOT_ENOUGH_REVIEWS");
        assertThat(result.getSourceReviewCount()).isEqualTo(2);
    }

    @Test
    void offlineActivityShouldHideHistoricalSummaryBeforeReadingTaskOrCache() {
        Activity activity = new Activity();
        activity.setStatus(2);
        when(activityMapper.selectById(2L)).thenReturn(activity);

        assertBusinessError(() -> service.query("ACTIVITY", 2L), ErrorCode.ACTIVITY_NOT_FOUND);

        verifyNoInteractions(persistenceService, cacheRepository);
    }

    @Test
    void shouldMapGeneratingAndUnavailableWithoutLeakingErrors() {
        prepareMovie(5);
        AiSummaryTask task = task("RETRY_WAIT", null, 2L, 1L);
        when(persistenceService.findTask(AiSummaryTargetType.MOVIE, 1L)).thenReturn(task);
        assertThat(service.query("MOVIE", 1L).getStatus()).isEqualTo("GENERATING");

        task.setStatus("DEAD");
        task.setLastErrorCode("AI_AUTH_FAILED");
        ReviewAiSummaryVO unavailable = service.query("MOVIE", 1L);
        assertThat(unavailable.getStatus()).isEqualTo("UNAVAILABLE");
        assertThat(unavailable.toString()).doesNotContain("AI_AUTH_FAILED");
    }

    @Test
    void shouldMapCachedCurrentSummaryToReadyOrRefreshing() {
        prepareMovie(5);
        AiSummaryTask task = task("SUCCEEDED", 9L, 3L, 3L);
        when(persistenceService.findTask(AiSummaryTargetType.MOVIE, 1L)).thenReturn(task);
        when(cacheRepository.find(AiSummaryTargetType.MOVIE, 1L))
                .thenReturn(Optional.of(cache(9L)));

        ReviewAiSummaryVO ready = service.query("MOVIE", 1L);
        assertThat(ready.getStatus()).isEqualTo("READY");
        assertThat(ready.getStale()).isFalse();
        assertThat(ready.getPros()).containsExactly("表演好");

        task.setStatus("PENDING");
        task.setDirtyVersion(4L);
        ReviewAiSummaryVO refreshing = service.query("MOVIE", 1L);
        assertThat(refreshing.getStatus()).isEqualTo("REFRESHING");
        assertThat(refreshing.getStale()).isTrue();
    }

    @Test
    void missingTaskWithEnoughReviewsShouldBeInternalErrorNotSynchronousGeneration() {
        prepareMovie(3);
        when(persistenceService.findTask(AiSummaryTargetType.MOVIE, 1L)).thenReturn(null);

        assertBusinessError(() -> service.query("MOVIE", 1L), ErrorCode.INTERNAL_ERROR);
    }

    private void prepareMovie(int count) {
        when(movieMapper.selectById(1L)).thenReturn(new Movie());
        when(reviewMapper.countActiveByTarget("MOVIE", 1L)).thenReturn(count);
    }

    private AiSummaryTask task(String status, Long summaryId, Long dirty, Long processed) {
        AiSummaryTask task = new AiSummaryTask();
        task.setStatus(status);
        task.setCurrentSummaryId(summaryId);
        task.setDirtyVersion(dirty);
        task.setProcessedVersion(processed);
        return task;
    }

    private AiReviewSummaryCacheValue cache(Long id) {
        AiReviewSummaryCacheValue value = new AiReviewSummaryCacheValue();
        value.setSummaryId(id);
        value.setTargetType("MOVIE");
        value.setTargetId(1L);
        value.setSummary("整体不错");
        value.setPros("[\"表演好\"]");
        value.setCons("[]");
        value.setSuitableFor("[\"剧情片观众\"]");
        value.setWarningPoints("[]");
        value.setSourceReviewCount(5);
        value.setInputReviewCount(5);
        value.setVersion(1);
        value.setPromptVersion("review-summary-v1");
        value.setGeneratedAt(LocalDateTime.of(2026, 9, 19, 12, 0));
        return value;
    }

    private void assertBusinessError(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(code);
    }
}
