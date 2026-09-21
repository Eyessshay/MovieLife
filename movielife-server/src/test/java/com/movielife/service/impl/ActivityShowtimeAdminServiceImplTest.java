package com.movielife.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.movielife.dto.AdminActivityShowtimeQueryDTO;
import com.movielife.dto.CreateActivityShowtimeDTO;
import com.movielife.entity.Activity;
import com.movielife.entity.Hall;
import com.movielife.entity.Showtime;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.mapper.ActivityMapper;
import com.movielife.mapper.HallMapper;
import com.movielife.mapper.ShowtimeMapper;
import com.movielife.mapper.VenueStaffMapper;
import com.movielife.vo.AdminActivityShowtimeVO;
import com.movielife.vo.PageVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.springframework.dao.DuplicateKeyException;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivityShowtimeAdminServiceImplTest {

    @Mock private VenueStaffMapper venueStaffMapper;
    @Mock private ShowtimeMapper showtimeMapper;
    @Mock private ActivityMapper activityMapper;
    @Mock private HallMapper hallMapper;
    @Mock private ActivityShowtimeCreationTransactionService creationTransactionService;

    private ActivityShowtimeAdminServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ActivityShowtimeAdminServiceImpl(
                venueStaffMapper, showtimeMapper, activityMapper, hallMapper,
                creationTransactionService);
    }

    @Test
    void shouldReturnStablePageAndBatchMapRelations() {
        AdminActivityShowtimeQueryDTO query = new AdminActivityShowtimeQueryDTO();
        query.setPage(2);
        query.setPageSize(10);
        query.setShowtimeStatus(0);
        query.setStartDate(LocalDate.of(2026, 9, 1));
        query.setEndDate(LocalDate.of(2026, 9, 30));
        Showtime first = showtime(1002L, 302L, 602L, 0, 80, 60);
        Showtime second = showtime(1001L, 301L, 601L, 2, 100, 0);
        Page<Showtime> page = new Page<>(2, 10, 25);
        page.setRecords(Arrays.asList(first, second));
        when(venueStaffMapper.selectActiveId(7L, 501L)).thenReturn(71L);
        when(showtimeMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);
        when(activityMapper.selectBatchIds(anyCollection())).thenReturn(Arrays.asList(
                activity(301L, "活动一"), activity(302L, "活动二")));
        when(hallMapper.selectBatchIds(anyCollection())).thenReturn(Arrays.asList(
                hall(601L, 501L, "一号厅"), hall(602L, 501L, "二号厅")));

        PageVO<AdminActivityShowtimeVO> result = service.queryShowtimes(7L, 501L, query);

        assertThat(result.getTotal()).isEqualTo(25);
        assertThat(result.getPage()).isEqualTo(2);
        assertThat(result.getPageSize()).isEqualTo(10);
        assertThat(result.getList()).extracting(AdminActivityShowtimeVO::getShowtimeId)
                .containsExactly(1002L, 1001L);
        assertThat(result.getList()).extracting(AdminActivityShowtimeVO::getActivityTitle)
                .containsExactly("活动二", "活动一");
        verify(activityMapper).selectBatchIds(anyCollection());
        verify(hallMapper).selectBatchIds(anyCollection());
    }

    @Test
    void shouldReturnEmptyPageWithoutRelationQueries() {
        AdminActivityShowtimeQueryDTO query = new AdminActivityShowtimeQueryDTO();
        when(venueStaffMapper.selectActiveId(7L, 501L)).thenReturn(71L);
        Page<Showtime> page = new Page<>(1, 20, 0);
        page.setRecords(Collections.emptyList());
        when(showtimeMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);

        PageVO<AdminActivityShowtimeVO> result = service.queryShowtimes(7L, 501L, query);

        assertThat(result.getList()).isEmpty();
        assertThat(result.getTotal()).isZero();
        verify(activityMapper, never()).selectBatchIds(anyCollection());
        verify(hallMapper, never()).selectBatchIds(anyCollection());
    }

    @Test
    void shouldRequireActiveVenueStaffBeforeQueryingShowtimes() {
        when(venueStaffMapper.selectActiveId(7L, 501L)).thenReturn(null);

        assertError(() -> service.queryShowtimes(
                7L, 501L, new AdminActivityShowtimeQueryDTO()),
                ErrorCode.VENUE_MANAGE_FORBIDDEN);

        verify(showtimeMapper, never()).selectPage(any(Page.class), any(Wrapper.class));
    }

    @Test
    void shouldRejectInvalidIdentityIdsAndDateRange() {
        assertError(() -> service.queryShowtimes(
                null, 501L, new AdminActivityShowtimeQueryDTO()), ErrorCode.UNAUTHORIZED);
        assertError(() -> service.queryShowtimes(
                7L, 0L, new AdminActivityShowtimeQueryDTO()), ErrorCode.INVALID_ID);
        assertError(() -> service.queryShowtime(7L, 501L, 0L), ErrorCode.INVALID_ID);

        AdminActivityShowtimeQueryDTO query = new AdminActivityShowtimeQueryDTO();
        query.setStartDate(LocalDate.of(2026, 10, 1));
        query.setEndDate(LocalDate.of(2026, 9, 30));
        assertError(() -> service.queryShowtimes(7L, 501L, query), ErrorCode.INVALID_PARAM);
        verify(venueStaffMapper, never()).selectActiveId(7L, 501L);
    }

    @Test
    void shouldDefensivelyRejectInvalidDirectQueryArguments() {
        AdminActivityShowtimeQueryDTO invalidPage = new AdminActivityShowtimeQueryDTO();
        invalidPage.setPage(0);
        assertError(() -> service.queryShowtimes(7L, 501L, invalidPage),
                ErrorCode.INVALID_PAGE);

        AdminActivityShowtimeQueryDTO invalidPageSize = new AdminActivityShowtimeQueryDTO();
        invalidPageSize.setPageSize(101);
        assertError(() -> service.queryShowtimes(7L, 501L, invalidPageSize),
                ErrorCode.PAGE_SIZE_TOO_LARGE);

        AdminActivityShowtimeQueryDTO invalidStatus = new AdminActivityShowtimeQueryDTO();
        invalidStatus.setShowtimeStatus(3);
        assertError(() -> service.queryShowtimes(7L, 501L, invalidStatus),
                ErrorCode.INVALID_SHOWTIME_STATUS);
        verify(venueStaffMapper, never()).selectActiveId(7L, 501L);
    }

    @Test
    void shouldReturnDetailForAuthorizedVenue() {
        when(venueStaffMapper.selectActiveId(7L, 501L)).thenReturn(71L);
        when(showtimeMapper.selectOne(any(Wrapper.class)))
                .thenReturn(showtime(1001L, 301L, 601L, 0, 100, 80));
        when(activityMapper.selectBatchIds(anyCollection()))
                .thenReturn(Collections.singletonList(activity(301L, "活动一")));
        when(hallMapper.selectBatchIds(anyCollection()))
                .thenReturn(Collections.singletonList(hall(601L, 501L, "一号厅")));

        AdminActivityShowtimeVO result = service.queryShowtime(7L, 501L, 1001L);

        assertThat(result.getShowtimeId()).isEqualTo(1001L);
        assertThat(result.getStatus()).isZero();
        assertThat(result.getAvailableSeats()).isEqualTo(80);
    }

    @Test
    void shouldHideMissingWrongVenueAndMovieAsShowtimeNotFound() {
        when(venueStaffMapper.selectActiveId(7L, 501L)).thenReturn(71L);
        when(showtimeMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        assertError(() -> service.queryShowtime(7L, 501L, 1001L),
                ErrorCode.SHOWTIME_NOT_FOUND);
        verify(activityMapper, never()).selectBatchIds(anyCollection());
    }

    @Test
    void shouldFailClosedForBrokenHallOrInventoryRelation() {
        when(venueStaffMapper.selectActiveId(7L, 501L)).thenReturn(71L);
        when(showtimeMapper.selectOne(any(Wrapper.class)))
                .thenReturn(showtime(1001L, 301L, 601L, 1, 10, 11));
        when(activityMapper.selectBatchIds(anyCollection()))
                .thenReturn(Collections.singletonList(activity(301L, "活动一")));
        when(hallMapper.selectBatchIds(anyCollection()))
                .thenReturn(Collections.singletonList(hall(601L, 999L, "错误场馆厅")));

        assertError(() -> service.queryShowtime(7L, 501L, 1001L), ErrorCode.INTERNAL_ERROR);
    }

    @Test
    void shouldReturnMatchingExistingCreationEvenAfterItsStartTime() {
        CreateActivityShowtimeDTO request = createRequest("req-existing",
                LocalDateTime.of(2020, 9, 20, 19, 30));
        Showtime existing = showtime(1001L, 301L, 601L, 1, 100, 100);
        existing.setCreateRequestId("req-existing");
        existing.setStartTime(request.getStartTime());
        existing.setEndTime(request.getEndTime());
        when(venueStaffMapper.selectActiveId(7L, 501L)).thenReturn(71L);
        when(showtimeMapper.selectByVenueAndCreateRequestId(501L, "req-existing"))
                .thenReturn(existing);
        when(showtimeMapper.selectOne(any(Wrapper.class))).thenReturn(existing);
        when(activityMapper.selectBatchIds(anyCollection()))
                .thenReturn(Collections.singletonList(activity(301L, "活动一")));
        when(hallMapper.selectBatchIds(anyCollection()))
                .thenReturn(Collections.singletonList(hall(601L, 501L, "一号厅")));

        AdminActivityShowtimeVO result = service.createShowtime(7L, 501L, request);

        assertThat(result.getShowtimeId()).isEqualTo(1001L);
        verify(creationTransactionService, never()).create(any(), any(), any());
    }

    @Test
    void shouldRejectPastNewCreationAndExistingRequestMismatch() {
        CreateActivityShowtimeDTO past = createRequest("req-past",
                LocalDateTime.of(2020, 9, 20, 19, 30));
        when(venueStaffMapper.selectActiveId(7L, 501L)).thenReturn(71L);
        assertError(() -> service.createShowtime(7L, 501L, past), ErrorCode.INVALID_PARAM);

        CreateActivityShowtimeDTO mismatch = createRequest("req-used",
                LocalDateTime.of(2035, 9, 20, 19, 30));
        Showtime existing = showtime(1001L, 999L, 601L, 1, 100, 100);
        existing.setCreateRequestId("req-used");
        existing.setStartTime(mismatch.getStartTime());
        existing.setEndTime(mismatch.getEndTime());
        when(showtimeMapper.selectByVenueAndCreateRequestId(501L, "req-used"))
                .thenReturn(existing);
        assertError(() -> service.createShowtime(7L, 501L, mismatch),
                ErrorCode.SHOWTIME_CREATE_REQUEST_MISMATCH);
        verify(creationTransactionService, never()).create(any(), any(), any());
    }

    @Test
    void shouldRecoverConcurrentDuplicateUsingPersistedRequest() {
        CreateActivityShowtimeDTO request = createRequest("req-race",
                LocalDateTime.of(2035, 9, 20, 19, 30));
        Showtime concurrent = showtime(1001L, 301L, 601L, 1, 2, 2);
        concurrent.setCreateRequestId("req-race");
        concurrent.setStartTime(request.getStartTime());
        concurrent.setEndTime(request.getEndTime());
        when(venueStaffMapper.selectActiveId(7L, 501L)).thenReturn(71L);
        when(showtimeMapper.selectByVenueAndCreateRequestId(501L, "req-race"))
                .thenReturn(null, concurrent);
        doThrow(new DuplicateKeyException("duplicate"))
                .when(creationTransactionService).create(any(), any(), any());
        when(showtimeMapper.selectOne(any(Wrapper.class))).thenReturn(concurrent);
        when(activityMapper.selectBatchIds(anyCollection()))
                .thenReturn(Collections.singletonList(activity(301L, "活动一")));
        when(hallMapper.selectBatchIds(anyCollection()))
                .thenReturn(Collections.singletonList(hall(601L, 501L, "一号厅")));

        assertThat(service.createShowtime(7L, 501L, request).getShowtimeId())
                .isEqualTo(1001L);
    }

    private Showtime showtime(Long id, Long activityId, Long hallId,
                              int status, int totalSeats, int availableSeats) {
        Showtime showtime = new Showtime();
        showtime.setId(id);
        showtime.setBizType("ACTIVITY");
        showtime.setBizId(activityId);
        showtime.setVenueId(501L);
        showtime.setHallId(hallId);
        showtime.setStartTime(LocalDateTime.of(2026, 9, 20, 19, 30));
        showtime.setEndTime(LocalDateTime.of(2026, 9, 20, 21, 30));
        showtime.setBasePrice(new BigDecimal("180.00"));
        showtime.setStatus(status);
        showtime.setTotalSeats(totalSeats);
        showtime.setAvailableSeats(availableSeats);
        return showtime;
    }

    private Activity activity(Long id, String title) {
        Activity activity = new Activity();
        activity.setId(id);
        activity.setTitle(title);
        return activity;
    }

    private CreateActivityShowtimeDTO createRequest(String requestId, LocalDateTime startTime) {
        CreateActivityShowtimeDTO request = new CreateActivityShowtimeDTO();
        request.setRequestId(requestId);
        request.setActivityId(301L);
        request.setHallId(601L);
        request.setStartTime(startTime);
        request.setEndTime(startTime.plusHours(2));
        request.setBasePrice(new BigDecimal("180.00"));
        return request;
    }

    private Hall hall(Long id, Long venueId, String name) {
        Hall hall = new Hall();
        hall.setId(id);
        hall.setVenueId(venueId);
        hall.setName(name);
        return hall;
    }

    private void assertError(Runnable action, ErrorCode errorCode) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }
}
