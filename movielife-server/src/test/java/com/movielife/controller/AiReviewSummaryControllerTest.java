package com.movielife.controller;

import com.movielife.enums.AiSummaryPublicStatus;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.service.MovieReviewAiSummaryQueryService;
import com.movielife.vo.ReviewAiSummaryVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AiReviewSummaryController.class)
class AiReviewSummaryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MovieReviewAiSummaryQueryService queryService;
    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void anonymousMovieSummaryShouldExposeStructuredArrays() throws Exception {
        ReviewAiSummaryVO result = new ReviewAiSummaryVO();
        result.setTargetType("MOVIE");
        result.setTargetId(1L);
        result.setStatus(AiSummaryPublicStatus.READY.name());
        result.setSummary("整体不错");
        result.setPros(Collections.singletonList("表演自然"));
        result.setCons(Collections.emptyList());
        result.setSuitableFor(Collections.singletonList("剧情片观众"));
        result.setWarningPoints(Collections.emptyList());
        result.setStale(false);
        when(queryService.query("MOVIE", 1L)).thenReturn(result);

        mockMvc.perform(get("/api/ai/summaries/MOVIE/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andExpect(jsonPath("$.data.pros[0]").value("表演自然"))
                .andExpect(jsonPath("$.data.stale").value(false));
    }

    @Test
    void activityShouldRemainUnavailableInStepFourteenFour() throws Exception {
        when(queryService.query("ACTIVITY", 1L))
                .thenThrow(new BusinessException(ErrorCode.INVALID_TARGET_TYPE));

        mockMvc.perform(get("/api/ai/summaries/ACTIVITY/1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TARGET_TYPE"));
    }

    @Test
    void malformedIdShouldUseExistingInvalidParamHandling() throws Exception {
        mockMvc.perform(get("/api/ai/summaries/MOVIE/not-a-number"))
                .andExpect(status().isBadRequest());
    }
}
