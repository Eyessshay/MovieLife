package com.movielife.service.impl;

import com.movielife.dto.ActivityShowtimeQueryDTO;
import com.movielife.entity.Activity;
import com.movielife.entity.Hall;
import com.movielife.entity.Showtime;
import com.movielife.entity.Venue;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.mapper.ActivityMapper;
import com.movielife.mapper.HallMapper;
import com.movielife.mapper.ShowtimeMapper;
import com.movielife.mapper.VenueMapper;
import com.movielife.vo.ActivityShowtimeVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivityServiceImplTest {

    @Mock private ActivityMapper activityMapper;
    @Mock private ShowtimeMapper showtimeMapper;
    @Mock private VenueMapper venueMapper;
    @Mock private HallMapper hallMapper;

    private ActivityServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ActivityServiceImpl(activityMapper, showtimeMapper, venueMapper, hallMapper);
    }

    @Test
    void detailShouldReturnOnlyActiveActivity() {
        Activity activity = activity(1L, 1);
        activity.setDescription("现场演出");
        when(activityMapper.selectById(1L)).thenReturn(activity);

        assertThat(service.queryActivityDetail(1L).getDescription()).isEqualTo("现场演出");

        when(activityMapper.selectById(2L)).thenReturn(activity(2L, 0));
        assertError(() -> service.queryActivityDetail(2L), ErrorCode.ACTIVITY_NOT_FOUND);
        assertError(() -> service.queryActivityDetail(0L), ErrorCode.INVALID_ID);
    }

    @Test
    void showtimesShouldBatchRelationsAndFilterDisabledVenue() {
        when(activityMapper.selectById(1L)).thenReturn(activity(1L, 1));
        Showtime enabled = showtime(10L, 20L, 30L);
        Showtime disabled = showtime(11L, 21L, 31L);
        when(showtimeMapper.selectList(any())).thenReturn(Arrays.asList(enabled, disabled));
        when(venueMapper.selectBatchIds(any())).thenReturn(Arrays.asList(
                venue(20L, "有效剧场", 1), venue(21L, "停用剧场", 0)));
        when(hallMapper.selectBatchIds(any())).thenReturn(Arrays.asList(
                hall(30L, 20L, "主剧场"), hall(31L, 21L, "停用厅")));

        List<ActivityShowtimeVO> result = service.queryActivityShowtimes(
                1L, new ActivityShowtimeQueryDTO());

        assertThat(result).extracting(ActivityShowtimeVO::getShowtimeId).containsExactly(10L);
        assertThat(result.get(0).getVenueName()).isEqualTo("有效剧场");
        verify(venueMapper).selectBatchIds(any());
        verify(hallMapper).selectBatchIds(any());
    }

    @Test
    void emptyShowtimesShouldAvoidRelationQueries() {
        when(activityMapper.selectById(1L)).thenReturn(activity(1L, 1));
        when(showtimeMapper.selectList(any())).thenReturn(Collections.emptyList());

        assertThat(service.queryActivityShowtimes(1L, new ActivityShowtimeQueryDTO())).isEmpty();
        verify(venueMapper, never()).selectBatchIds(any());
        verify(hallMapper, never()).selectBatchIds(any());
    }

    @Test
    void brokenShowtimeRelationShouldFailClosed() {
        when(activityMapper.selectById(1L)).thenReturn(activity(1L, 1));
        when(showtimeMapper.selectList(any())).thenReturn(Collections.singletonList(
                showtime(10L, 20L, 30L)));
        when(venueMapper.selectBatchIds(any())).thenReturn(Collections.singletonList(
                venue(20L, "有效剧场", 1)));
        when(hallMapper.selectBatchIds(any())).thenReturn(Collections.singletonList(
                hall(30L, 999L, "错误归属厅")));

        assertError(() -> service.queryActivityShowtimes(
                1L, new ActivityShowtimeQueryDTO()), ErrorCode.INTERNAL_ERROR);
    }

    private Activity activity(Long id, int status) {
        Activity value = new Activity();
        value.setId(id);
        value.setTitle("示例活动" + id);
        value.setType("talkshow");
        value.setCity("上海");
        value.setPriceMin(new BigDecimal("199.00"));
        value.setPriceMax(new BigDecimal("299.00"));
        value.setStatus(status);
        return value;
    }

    private Showtime showtime(Long id, Long venueId, Long hallId) {
        Showtime value = new Showtime();
        value.setId(id);
        value.setBizType("ACTIVITY");
        value.setBizId(1L);
        value.setVenueId(venueId);
        value.setHallId(hallId);
        value.setStartTime(LocalDateTime.now().plusDays(1));
        value.setEndTime(LocalDateTime.now().plusDays(1).plusHours(2));
        value.setBasePrice(new BigDecimal("199.00"));
        value.setTotalSeats(6);
        value.setAvailableSeats(6);
        value.setStatus(1);
        return value;
    }

    private Venue venue(Long id, String name, int status) {
        Venue value = new Venue();
        value.setId(id);
        value.setName(name);
        value.setStatus(status);
        return value;
    }

    private Hall hall(Long id, Long venueId, String name) {
        Hall value = new Hall();
        value.setId(id);
        value.setVenueId(venueId);
        value.setName(name);
        return value;
    }

    private void assertError(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(expected);
    }
}
