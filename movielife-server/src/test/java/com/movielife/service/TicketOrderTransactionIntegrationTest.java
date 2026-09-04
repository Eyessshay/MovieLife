package com.movielife.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.movielife.constants.RedisConstants;
import com.movielife.dto.CreateTicketOrderDTO;
import com.movielife.dto.SeatLockRequest;
import com.movielife.dto.UserDTO;
import com.movielife.entity.Hall;
import com.movielife.entity.Movie;
import com.movielife.entity.Seat;
import com.movielife.entity.Showtime;
import com.movielife.entity.ShowtimeSeat;
import com.movielife.entity.TicketOrder;
import com.movielife.entity.TicketOrderItem;
import com.movielife.entity.User;
import com.movielife.entity.Venue;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.mapper.HallMapper;
import com.movielife.mapper.MovieMapper;
import com.movielife.mapper.SeatMapper;
import com.movielife.mapper.ShowtimeMapper;
import com.movielife.mapper.ShowtimeSeatMapper;
import com.movielife.mapper.TicketOrderItemMapper;
import com.movielife.mapper.TicketOrderMapper;
import com.movielife.mapper.UserMapper;
import com.movielife.mapper.VenueMapper;
import com.movielife.utils.UserHolder;
import com.movielife.vo.CreateTicketOrderVO;
import com.movielife.vo.SeatLockVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "movielife.startup-check.enabled=false")
class TicketOrderTransactionIntegrationTest {

    @Autowired private TicketOrderService ticketOrderService;
    @Autowired private SeatLockService seatLockService;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private UserMapper userMapper;
    @Autowired private MovieMapper movieMapper;
    @Autowired private VenueMapper venueMapper;
    @Autowired private HallMapper hallMapper;
    @Autowired private SeatMapper seatMapper;
    @Autowired private ShowtimeMapper showtimeMapper;
    @Autowired private ShowtimeSeatMapper showtimeSeatMapper;
    @Autowired private TicketOrderMapper ticketOrderMapper;
    @Autowired private TicketOrderItemMapper ticketOrderItemMapper;

    private final Set<String> cleanupKeys = new LinkedHashSet<>();
    private TestContext context;

    @BeforeEach
    void setUp() {
        User user = userMapper.selectById(1L);
        assertThat(user).isNotNull();
        UserDTO userDTO = new UserDTO();
        userDTO.setId(user.getId());
        UserHolder.saveUser(userDTO);
    }

    @AfterEach
    void cleanUp() {
        UserHolder.removeUser();
        if (!cleanupKeys.isEmpty()) {
            redisTemplate.delete(cleanupKeys);
        }
        if (context != null) {
            List<TicketOrder> orders = ticketOrderMapper.selectList(
                    new LambdaQueryWrapper<TicketOrder>()
                            .eq(TicketOrder::getShowtimeId, context.showtime.getId()));
            for (TicketOrder order : orders) {
                ticketOrderItemMapper.delete(new LambdaQueryWrapper<TicketOrderItem>()
                        .eq(TicketOrderItem::getOrderId, order.getId()));
            }
            ticketOrderMapper.delete(new LambdaQueryWrapper<TicketOrder>()
                    .eq(TicketOrder::getShowtimeId, context.showtime.getId()));
            showtimeSeatMapper.delete(new LambdaQueryWrapper<ShowtimeSeat>()
                    .eq(ShowtimeSeat::getShowtimeId, context.showtime.getId()));
            showtimeMapper.deleteById(context.showtime.getId());
            for (Long seatId : context.seatIds) {
                seatMapper.deleteById(seatId);
            }
            hallMapper.deleteById(context.hallId);
            venueMapper.deleteById(context.venueId);
            movieMapper.deleteById(context.movieId);
        }
    }

    @Test
    void shouldCreateOrderThenConditionallyRemoveRedisLocks() {
        context = insertShowtimeWithSeats(2, LocalDateTime.now().plusHours(2));
        LockedRequest locked = lockAndBuildRequest(context);

        CreateTicketOrderVO result = ticketOrderService.createOrder(1L, locked.request);

        TicketOrder order = ticketOrderMapper.selectById(result.getOrderId());
        assertThat(order.getStatus()).isEqualTo("UNPAID");
        assertThat(order.getLockToken()).isEqualTo(locked.request.getLockToken());
        assertThat(order.getTotalAmount()).isEqualByComparingTo("95.00");
        assertThat(order.getExpireTime()).isAfter(LocalDateTime.now().plusMinutes(14));

        List<TicketOrderItem> items = ticketOrderItemMapper.selectList(
                new LambdaQueryWrapper<TicketOrderItem>()
                        .eq(TicketOrderItem::getOrderId, order.getId())
                        .orderByAsc(TicketOrderItem::getSeatId));
        assertThat(items).hasSize(2);
        assertThat(items).extracting(TicketOrderItem::getPrice)
                .containsExactly(new BigDecimal("40.00"), new BigDecimal("55.00"));
        assertThat(items).allSatisfy(item -> {
            assertThat(item.getTicketCode()).isNull();
            assertThat(item.getTicketStatus()).isZero();
        });

        List<ShowtimeSeat> states = queryStates();
        assertThat(states).allSatisfy(state -> {
            assertThat(state.getStatus()).isEqualTo(1);
            assertThat(state.getLockOrderId()).isEqualTo(order.getId());
            assertThat(state.getVersion()).isEqualTo(1);
        });
        assertThat(showtimeMapper.selectById(context.showtime.getId()).getAvailableSeats()).isZero();

        assertThat(redisTemplate.opsForValue().get(locked.metadataKey)).isNull();
        assertThat(redisTemplate.opsForValue().multiGet(locked.seatKeys))
                .allMatch(value -> value == null);

        CreateTicketOrderVO idempotent = ticketOrderService.createOrder(1L, locked.request);
        assertThat(idempotent.getOrderId()).isEqualTo(order.getId());
        assertThat(ticketOrderMapper.selectCount(new LambdaQueryWrapper<TicketOrder>()
                .eq(TicketOrder::getLockToken, locked.request.getLockToken()))).isEqualTo(1);
    }

    @Test
    void availableSeatCounterFailureShouldRollbackEverythingAndAllowRetry() {
        context = insertShowtimeWithSeats(1, LocalDateTime.now().plusHours(2));
        LockedRequest locked = lockAndBuildRequest(context);
        Long itemCountBefore = ticketOrderItemMapper.selectCount(null);

        assertThatThrownBy(() -> ticketOrderService.createOrder(1L, locked.request))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.INTERNAL_ERROR);

        assertThat(ticketOrderMapper.selectCount(new LambdaQueryWrapper<TicketOrder>()
                .eq(TicketOrder::getLockToken, locked.request.getLockToken()))).isZero();
        assertThat(ticketOrderItemMapper.selectCount(null)).isEqualTo(itemCountBefore);
        assertThat(queryStates()).allSatisfy(state -> {
            assertThat(state.getStatus()).isZero();
            assertThat(state.getLockOrderId()).isNull();
            assertThat(state.getVersion()).isZero();
        });
        assertThat(showtimeMapper.selectById(context.showtime.getId()).getAvailableSeats()).isEqualTo(1);
        assertThat(redisTemplate.opsForValue().get(locked.metadataKey)).isNotNull();

        Showtime repaired = new Showtime();
        repaired.setId(context.showtime.getId());
        repaired.setAvailableSeats(2);
        assertThat(showtimeMapper.updateById(repaired)).isEqualTo(1);

        CreateTicketOrderVO retry = ticketOrderService.createOrder(1L, locked.request);
        assertThat(retry.getOrderId()).isNotNull();
        assertThat(queryStates()).allMatch(state -> retry.getOrderId().equals(state.getLockOrderId()));
        assertThat(showtimeMapper.selectById(context.showtime.getId()).getAvailableSeats()).isZero();
        assertThat(redisTemplate.opsForValue().get(locked.metadataKey)).isNull();
        assertThat(redisTemplate.opsForValue().multiGet(locked.seatKeys))
                .allMatch(value -> value == null);
    }

    @Test
    void expireTimeShouldBeCappedByNearShowtimeStart() {
        LocalDateTime startTime = LocalDateTime.now().plusMinutes(10).withNano(0);
        context = insertShowtimeWithSeats(2, startTime);
        LockedRequest locked = lockAndBuildRequest(context);

        CreateTicketOrderVO result = ticketOrderService.createOrder(1L, locked.request);

        assertThat(result.getExpireTime()).isEqualTo(startTime);
    }

    @Test
    void conditionalCleanupShouldBeIdempotentAndKeepSeatOwnedByAnotherToken() {
        context = insertShowtimeWithSeats(2, LocalDateTime.now().plusHours(2));
        LockedRequest locked = lockAndBuildRequest(context);
        String reownedSeatKey = locked.seatKeys.get(1);
        redisTemplate.opsForValue().set(reownedSeatKey, "another-token", 30, TimeUnit.SECONDS);
        redisTemplate.delete(locked.metadataKey);

        seatLockService.cleanupLock(context.showtime.getId(), context.seatIds,
                locked.request.getLockToken());
        seatLockService.cleanupLock(context.showtime.getId(), context.seatIds,
                locked.request.getLockToken());

        assertThat(redisTemplate.opsForValue().get(locked.metadataKey)).isNull();
        assertThat(redisTemplate.opsForValue().get(locked.seatKeys.get(0))).isNull();
        assertThat(redisTemplate.opsForValue().get(reownedSeatKey)).isEqualTo("another-token");
    }

    private TestContext insertShowtimeWithSeats(int availableSeats, LocalDateTime startTime) {
        Movie movie = new Movie();
        movie.setTitle("订单事务联调电影");
        movie.setAvgScore(BigDecimal.ZERO);
        movie.setScoreCount(0);
        movie.setStatus(1);
        movieMapper.insert(movie);

        Venue venue = new Venue();
        venue.setName("订单事务联调影院");
        venue.setType("cinema");
        venue.setCity("上海");
        venue.setAddress("订单事务联调地址");
        venue.setAvgScore(BigDecimal.ZERO);
        venue.setStatus(1);
        venueMapper.insert(venue);

        Hall hall = new Hall();
        hall.setVenueId(venue.getId());
        hall.setName("订单事务联调厅");
        hallMapper.insert(hall);

        List<Long> seatIds = new ArrayList<>();
        for (int index = 1; index <= 2; index++) {
            Seat seat = new Seat();
            seat.setHallId(hall.getId());
            seat.setRowNo(1);
            seat.setColNo(index);
            seat.setSeatNo("1排" + index + "座");
            seat.setSeatType("NORMAL");
            seat.setBaseStatus(1);
            seatMapper.insert(seat);
            seatIds.add(seat.getId());
        }

        Showtime showtime = new Showtime();
        showtime.setBizType("MOVIE");
        showtime.setBizId(movie.getId());
        showtime.setVenueId(venue.getId());
        showtime.setHallId(hall.getId());
        showtime.setStartTime(startTime);
        showtime.setEndTime(startTime.plusHours(2));
        showtime.setBasePrice(new BigDecimal("10.00"));
        showtime.setTotalSeats(2);
        showtime.setAvailableSeats(availableSeats);
        showtime.setStatus(1);
        showtimeMapper.insert(showtime);

        for (int index = 0; index < seatIds.size(); index++) {
            ShowtimeSeat state = new ShowtimeSeat();
            state.setShowtimeId(showtime.getId());
            state.setSeatId(seatIds.get(index));
            state.setStatus(0);
            state.setPrice(index == 0 ? new BigDecimal("40.00") : new BigDecimal("55.00"));
            state.setVersion(0);
            showtimeSeatMapper.insert(state);
        }
        return new TestContext(movie.getId(), venue.getId(), hall.getId(), showtime, seatIds);
    }

    private LockedRequest lockAndBuildRequest(TestContext testContext) {
        SeatLockRequest lockRequest = new SeatLockRequest();
        lockRequest.setSeatIds(testContext.seatIds);
        SeatLockVO lock = seatLockService.lock(testContext.showtime.getId(), lockRequest);

        CreateTicketOrderDTO request = new CreateTicketOrderDTO();
        request.setShowtimeId(testContext.showtime.getId());
        request.setSeatIds(Arrays.asList(testContext.seatIds.get(1), testContext.seatIds.get(0)));
        request.setLockToken(lock.getLockToken());

        String metadataKey = RedisConstants.SEAT_LOCK_TOKEN_KEY_PREFIX + lock.getLockToken();
        List<String> seatKeys = testContext.seatIds.stream()
                .map(seatId -> RedisConstants.SEAT_LOCK_KEY_PREFIX
                        + testContext.showtime.getId() + ":" + seatId)
                .collect(Collectors.toList());
        cleanupKeys.add(metadataKey);
        cleanupKeys.addAll(seatKeys);
        return new LockedRequest(request, metadataKey, seatKeys);
    }

    private List<ShowtimeSeat> queryStates() {
        return showtimeSeatMapper.selectList(new LambdaQueryWrapper<ShowtimeSeat>()
                .eq(ShowtimeSeat::getShowtimeId, context.showtime.getId())
                .orderByAsc(ShowtimeSeat::getSeatId));
    }

    private long ttl(String key) {
        Long value = redisTemplate.getExpire(key, TimeUnit.MILLISECONDS);
        return value == null ? -2L : value;
    }

    private static final class LockedRequest {
        private final CreateTicketOrderDTO request;
        private final String metadataKey;
        private final List<String> seatKeys;

        private LockedRequest(CreateTicketOrderDTO request, String metadataKey, List<String> seatKeys) {
            this.request = request;
            this.metadataKey = metadataKey;
            this.seatKeys = seatKeys;
        }
    }

    private static final class TestContext {
        private final Long movieId;
        private final Long venueId;
        private final Long hallId;
        private final Showtime showtime;
        private final List<Long> seatIds;

        private TestContext(Long movieId, Long venueId, Long hallId,
                            Showtime showtime, List<Long> seatIds) {
            this.movieId = movieId;
            this.venueId = venueId;
            this.hallId = hallId;
            this.showtime = showtime;
            this.seatIds = seatIds;
        }
    }
}
