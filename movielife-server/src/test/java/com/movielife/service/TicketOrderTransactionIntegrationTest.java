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
import com.movielife.entity.VenueStaff;
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
import com.movielife.mapper.VenueStaffMapper;
import com.movielife.utils.UserHolder;
import com.movielife.vo.CreateTicketOrderVO;
import com.movielife.vo.PayTicketOrderVO;
import com.movielife.vo.SeatLockVO;
import com.movielife.service.impl.TicketOrderCancellationService;
import com.movielife.service.impl.TicketVerificationService;
import com.movielife.service.TicketVerificationResult;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
    @Autowired private VenueStaffMapper venueStaffMapper;
    @Autowired private HallMapper hallMapper;
    @Autowired private SeatMapper seatMapper;
    @Autowired private ShowtimeMapper showtimeMapper;
    @Autowired private ShowtimeSeatMapper showtimeSeatMapper;
    @Autowired private TicketOrderMapper ticketOrderMapper;
    @Autowired private TicketOrderItemMapper ticketOrderItemMapper;
    @Autowired private TicketOrderCancellationService cancellationService;
    @Autowired private TicketVerificationService ticketVerificationService;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;

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
            venueStaffMapper.delete(new LambdaQueryWrapper<VenueStaff>()
                    .eq(VenueStaff::getVenueId, context.venueId));
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

    @Test
    void unpaidOrderCancellationShouldReleaseSeatsRestoreCounterAndBeIdempotent() {
        context = insertShowtimeWithSeats(2, LocalDateTime.now().plusHours(2));
        LockedRequest locked = lockAndBuildRequest(context);
        CreateTicketOrderVO created = ticketOrderService.createOrder(1L, locked.request);
        TicketOrder expired = new TicketOrder();
        expired.setId(created.getOrderId());
        expired.setExpireTime(LocalDateTime.now().minusMinutes(1));
        assertThat(ticketOrderMapper.updateById(expired)).isEqualTo(1);
        List<TicketOrderItem> itemsBefore = ticketOrderItemMapper.selectList(
                new LambdaQueryWrapper<TicketOrderItem>()
                        .eq(TicketOrderItem::getOrderId, created.getOrderId())
                        .orderByAsc(TicketOrderItem::getId));

        ticketOrderService.cancelOrder(1L, created.getOrderId());

        assertThat(ticketOrderMapper.selectById(created.getOrderId()).getStatus())
                .isEqualTo("CANCELED");
        assertThat(queryStates()).allSatisfy(state -> {
            assertThat(state.getStatus()).isZero();
            assertThat(state.getLockOrderId()).isNull();
            assertThat(state.getVersion()).isEqualTo(2);
        });
        assertThat(showtimeMapper.selectById(context.showtime.getId()).getAvailableSeats())
                .isEqualTo(2);
        assertThat(ticketOrderItemMapper.selectList(new LambdaQueryWrapper<TicketOrderItem>()
                .eq(TicketOrderItem::getOrderId, created.getOrderId())
                .orderByAsc(TicketOrderItem::getId))).isEqualTo(itemsBefore);

        ticketOrderService.cancelOrder(1L, created.getOrderId());

        assertThat(showtimeMapper.selectById(context.showtime.getId()).getAvailableSeats())
                .isEqualTo(2);
        assertThat(queryStates()).allSatisfy(state -> assertThat(state.getVersion()).isEqualTo(2));
    }

    @Test
    void paidOrderCancellationShouldConflictWithoutChangingSeatsOrCounter() {
        context = insertShowtimeWithSeats(2, LocalDateTime.now().plusHours(2));
        CreateTicketOrderVO created = ticketOrderService.createOrder(
                1L, lockAndBuildRequest(context).request);
        TicketOrder paid = new TicketOrder();
        paid.setId(created.getOrderId());
        paid.setStatus("PAID");
        assertThat(ticketOrderMapper.updateById(paid)).isEqualTo(1);
        List<ShowtimeSeat> statesBefore = queryStates();

        assertThatThrownBy(() -> ticketOrderService.cancelOrder(1L, created.getOrderId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.ORDER_STATUS_CONFLICT));

        assertThat(ticketOrderMapper.selectById(created.getOrderId()).getStatus()).isEqualTo("PAID");
        assertThat(queryStates()).isEqualTo(statesBefore);
        assertThat(showtimeMapper.selectById(context.showtime.getId()).getAvailableSeats()).isZero();
    }

    @Test
    void partialSeatStateAndWrongLockOwnerShouldRollbackEveryRelease() {
        context = insertShowtimeWithSeats(2, LocalDateTime.now().plusHours(2));
        CreateTicketOrderVO created = ticketOrderService.createOrder(
                1L, lockAndBuildRequest(context).request);
        ShowtimeSeat inconsistent = queryStates().get(1);
        inconsistent.setStatus(0);
        inconsistent.setLockOrderId(null);
        assertThat(showtimeSeatMapper.updateById(inconsistent)).isEqualTo(1);

        assertThatThrownBy(() -> ticketOrderService.cancelOrder(1L, created.getOrderId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INTERNAL_ERROR));
        assertThat(ticketOrderMapper.selectById(created.getOrderId()).getStatus())
                .isEqualTo("UNPAID");
        assertThat(queryStates().get(0).getLockOrderId()).isEqualTo(created.getOrderId());
        assertThat(showtimeMapper.selectById(context.showtime.getId()).getAvailableSeats()).isZero();

        inconsistent = queryStates().get(1);
        inconsistent.setStatus(1);
        inconsistent.setLockOrderId(created.getOrderId() + 1000);
        assertThat(showtimeSeatMapper.updateById(inconsistent)).isEqualTo(1);
        assertThatThrownBy(() -> ticketOrderService.cancelOrder(1L, created.getOrderId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INTERNAL_ERROR));
        assertThat(queryStates().get(0).getLockOrderId()).isEqualTo(created.getOrderId());
        assertThat(queryStates().get(1).getLockOrderId()).isEqualTo(created.getOrderId() + 1000);
        assertThat(ticketOrderMapper.selectById(created.getOrderId()).getStatus())
                .isEqualTo("UNPAID");
    }

    @Test
    void availableSeatCounterOverflowShouldRollbackReleasedSeats() {
        context = insertShowtimeWithSeats(2, LocalDateTime.now().plusHours(2));
        CreateTicketOrderVO created = ticketOrderService.createOrder(
                1L, lockAndBuildRequest(context).request);
        Showtime inconsistent = new Showtime();
        inconsistent.setId(context.showtime.getId());
        inconsistent.setAvailableSeats(2);
        assertThat(showtimeMapper.updateById(inconsistent)).isEqualTo(1);

        assertThatThrownBy(() -> ticketOrderService.cancelOrder(1L, created.getOrderId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INTERNAL_ERROR));

        assertThat(ticketOrderMapper.selectById(created.getOrderId()).getStatus())
                .isEqualTo("UNPAID");
        assertThat(queryStates()).allSatisfy(state -> {
            assertThat(state.getStatus()).isEqualTo(1);
            assertThat(state.getLockOrderId()).isEqualTo(created.getOrderId());
        });
        assertThat(showtimeMapper.selectById(context.showtime.getId()).getAvailableSeats())
                .isEqualTo(2);
    }

    @Test
    void concurrentDuplicateCancellationShouldRestoreAvailabilityOnlyOnce() throws Exception {
        context = insertShowtimeWithSeats(2, LocalDateTime.now().plusHours(2));
        CreateTicketOrderVO created = ticketOrderService.createOrder(
                1L, lockAndBuildRequest(context).request);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> first = executor.submit(() -> {
                await(start);
                ticketOrderService.cancelOrder(1L, created.getOrderId());
            });
            Future<?> second = executor.submit(() -> {
                await(start);
                ticketOrderService.cancelOrder(1L, created.getOrderId());
            });
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(ticketOrderMapper.selectById(created.getOrderId()).getStatus())
                .isEqualTo("CANCELED");
        assertThat(showtimeMapper.selectById(context.showtime.getId()).getAvailableSeats())
                .isEqualTo(2);
        assertThat(queryStates()).allSatisfy(state -> {
            assertThat(state.getStatus()).isZero();
            assertThat(state.getLockOrderId()).isNull();
            assertThat(state.getVersion()).isEqualTo(2);
        });
    }

    @Test
    void expiredUnpaidOrderShouldBeCanceledByTimeoutTransaction() {
        context = insertShowtimeWithSeats(2, LocalDateTime.now().plusHours(2));
        CreateTicketOrderVO created = ticketOrderService.createOrder(
                1L, lockAndBuildRequest(context).request);
        TicketOrder expired = new TicketOrder();
        expired.setId(created.getOrderId());
        expired.setExpireTime(LocalDateTime.now().minusSeconds(1));
        assertThat(ticketOrderMapper.updateById(expired)).isEqualTo(1);

        cancellationService.cancelExpiredOrder(created.getOrderId());

        assertThat(ticketOrderMapper.selectById(created.getOrderId()).getStatus())
                .isEqualTo("CANCELED");
        assertThat(showtimeMapper.selectById(context.showtime.getId()).getAvailableSeats())
                .isEqualTo(2);
        assertThat(queryStates()).allSatisfy(state -> {
            assertThat(state.getStatus()).isZero();
            assertThat(state.getLockOrderId()).isNull();
            assertThat(state.getVersion()).isEqualTo(2);
        });
    }

    @Test
    void timeoutTransactionShouldSkipFuturePaidAndCanceledOrders() {
        context = insertShowtimeWithSeats(2, LocalDateTime.now().plusHours(2));
        CreateTicketOrderVO created = ticketOrderService.createOrder(
                1L, lockAndBuildRequest(context).request);
        List<ShowtimeSeat> heldBefore = queryStates();

        cancellationService.cancelExpiredOrder(created.getOrderId());
        assertThat(ticketOrderMapper.selectById(created.getOrderId()).getStatus())
                .isEqualTo("UNPAID");
        assertThat(queryStates()).isEqualTo(heldBefore);

        TicketOrder paid = new TicketOrder();
        paid.setId(created.getOrderId());
        paid.setStatus("PAID");
        paid.setExpireTime(LocalDateTime.now().minusMinutes(1));
        assertThat(ticketOrderMapper.updateById(paid)).isEqualTo(1);
        cancellationService.cancelExpiredOrder(created.getOrderId());
        assertThat(ticketOrderMapper.selectById(created.getOrderId()).getStatus()).isEqualTo("PAID");
        assertThat(queryStates()).isEqualTo(heldBefore);

        TicketOrder canceled = new TicketOrder();
        canceled.setId(created.getOrderId());
        canceled.setStatus("CANCELED");
        assertThat(ticketOrderMapper.updateById(canceled)).isEqualTo(1);
        cancellationService.cancelExpiredOrder(created.getOrderId());
        assertThat(queryStates()).isEqualTo(heldBefore);
        assertThat(showtimeMapper.selectById(context.showtime.getId()).getAvailableSeats()).isZero();
    }

    @Test
    void activeAndTimeoutCancellationShouldCompeteWithoutDoubleRelease() throws Exception {
        context = insertShowtimeWithSeats(2, LocalDateTime.now().plusHours(2));
        CreateTicketOrderVO created = ticketOrderService.createOrder(
                1L, lockAndBuildRequest(context).request);
        TicketOrder expired = new TicketOrder();
        expired.setId(created.getOrderId());
        expired.setExpireTime(LocalDateTime.now().minusSeconds(1));
        assertThat(ticketOrderMapper.updateById(expired)).isEqualTo(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> active = executor.submit(() -> {
                await(start);
                ticketOrderService.cancelOrder(1L, created.getOrderId());
            });
            Future<?> timeout = executor.submit(() -> {
                await(start);
                cancellationService.cancelExpiredOrder(created.getOrderId());
            });
            start.countDown();
            active.get(10, TimeUnit.SECONDS);
            timeout.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(ticketOrderMapper.selectById(created.getOrderId()).getStatus())
                .isEqualTo("CANCELED");
        assertThat(showtimeMapper.selectById(context.showtime.getId()).getAvailableSeats())
                .isEqualTo(2);
        assertThat(queryStates()).allSatisfy(state -> {
            assertThat(state.getStatus()).isZero();
            assertThat(state.getLockOrderId()).isNull();
            assertThat(state.getVersion()).isEqualTo(2);
        });
    }

    @Test
    void paymentShouldSellHeldSeatsKeepCounterAndBeIdempotent() {
        context = insertShowtimeWithSeats(2, LocalDateTime.now().plusHours(2));
        CreateTicketOrderVO created = ticketOrderService.createOrder(
                1L, lockAndBuildRequest(context).request);
        int availableBefore = showtimeMapper.selectById(context.showtime.getId()).getAvailableSeats();

        PayTicketOrderVO first = ticketOrderService.payOrder(1L, created.getOrderId());
        PayTicketOrderVO second = ticketOrderService.payOrder(1L, created.getOrderId());

        TicketOrder paid = ticketOrderMapper.selectById(created.getOrderId());
        assertThat(paid.getStatus()).isEqualTo("PAID");
        assertThat(paid.getPayTime()).isEqualTo(first.getPayTime());
        assertThat(second.getPayTime()).isEqualTo(first.getPayTime());
        assertThat(queryStates()).allSatisfy(state -> {
            assertThat(state.getStatus()).isEqualTo(2);
            assertThat(state.getLockOrderId()).isEqualTo(created.getOrderId());
            assertThat(state.getVersion()).isEqualTo(2);
        });
        assertThat(showtimeMapper.selectById(context.showtime.getId()).getAvailableSeats())
                .isEqualTo(availableBefore);
    }

    @Test
    void expiredAndCanceledOrdersShouldNotBePaid() {
        context = insertShowtimeWithSeats(2, LocalDateTime.now().plusHours(2));
        CreateTicketOrderVO created = ticketOrderService.createOrder(
                1L, lockAndBuildRequest(context).request);
        TicketOrder expired = new TicketOrder();
        expired.setId(created.getOrderId());
        expired.setExpireTime(LocalDateTime.now().minusSeconds(1));
        assertThat(ticketOrderMapper.updateById(expired)).isEqualTo(1);
        List<ShowtimeSeat> held = queryStates();

        assertThatThrownBy(() -> ticketOrderService.payOrder(1L, created.getOrderId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.ORDER_EXPIRED));
        assertThat(ticketOrderMapper.selectById(created.getOrderId()).getStatus())
                .isEqualTo("UNPAID");
        assertThat(queryStates()).isEqualTo(held);

        ticketOrderService.cancelOrder(1L, created.getOrderId());
        assertThatThrownBy(() -> ticketOrderService.payOrder(1L, created.getOrderId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.ORDER_STATUS_CONFLICT));
    }

    @Test
    void partialSeatSaleShouldRollbackAllPaymentChanges() {
        context = insertShowtimeWithSeats(2, LocalDateTime.now().plusHours(2));
        CreateTicketOrderVO created = ticketOrderService.createOrder(
                1L, lockAndBuildRequest(context).request);
        ShowtimeSeat inconsistent = queryStates().get(1);
        inconsistent.setStatus(0);
        inconsistent.setLockOrderId(null);
        assertThat(showtimeSeatMapper.updateById(inconsistent)).isEqualTo(1);

        assertThatThrownBy(() -> ticketOrderService.payOrder(1L, created.getOrderId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INTERNAL_ERROR));

        assertThat(ticketOrderMapper.selectById(created.getOrderId()).getStatus())
                .isEqualTo("UNPAID");
        assertThat(ticketOrderMapper.selectById(created.getOrderId()).getPayTime()).isNull();
        assertThat(queryStates().get(0).getStatus()).isEqualTo(1);
        assertThat(queryStates().get(0).getLockOrderId()).isEqualTo(created.getOrderId());
        assertThat(showtimeMapper.selectById(context.showtime.getId()).getAvailableSeats()).isZero();
    }

    @Test
    void concurrentDuplicatePaymentShouldPreserveFirstPayTime() throws Exception {
        context = insertShowtimeWithSeats(2, LocalDateTime.now().plusHours(2));
        CreateTicketOrderVO created = ticketOrderService.createOrder(
                1L, lockAndBuildRequest(context).request);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<PayTicketOrderVO> first = executor.submit(() -> {
                await(start);
                return ticketOrderService.payOrder(1L, created.getOrderId());
            });
            Future<PayTicketOrderVO> second = executor.submit(() -> {
                await(start);
                return ticketOrderService.payOrder(1L, created.getOrderId());
            });
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS).getPayTime())
                    .isEqualTo(second.get(10, TimeUnit.SECONDS).getPayTime());
        } finally {
            executor.shutdownNow();
        }

        assertThat(ticketOrderMapper.selectById(created.getOrderId()).getStatus()).isEqualTo("PAID");
        assertThat(queryStates()).allSatisfy(state -> {
            assertThat(state.getStatus()).isEqualTo(2);
            assertThat(state.getVersion()).isEqualTo(2);
        });
        assertThat(showtimeMapper.selectById(context.showtime.getId()).getAvailableSeats()).isZero();
    }

    @Test
    void paymentAndActiveCancellationShouldLeaveOneCoherentFinalState() throws Exception {
        context = insertShowtimeWithSeats(2, LocalDateTime.now().plusHours(2));
        CreateTicketOrderVO created = ticketOrderService.createOrder(
                1L, lockAndBuildRequest(context).request);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> payment = executor.submit(() -> runIgnoringBusinessConflict(
                    start, () -> ticketOrderService.payOrder(1L, created.getOrderId())));
            Future<?> cancellation = executor.submit(() -> runIgnoringBusinessConflict(
                    start, () -> ticketOrderService.cancelOrder(1L, created.getOrderId())));
            start.countDown();
            payment.get(10, TimeUnit.SECONDS);
            cancellation.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        TicketOrder finalOrder = ticketOrderMapper.selectById(created.getOrderId());
        if ("PAID".equals(finalOrder.getStatus())) {
            assertThat(finalOrder.getPayTime()).isNotNull();
            assertThat(queryStates()).allSatisfy(state -> {
                assertThat(state.getStatus()).isEqualTo(2);
                assertThat(state.getLockOrderId()).isEqualTo(created.getOrderId());
            });
            assertThat(showtimeMapper.selectById(context.showtime.getId()).getAvailableSeats()).isZero();
        } else {
            assertThat(finalOrder.getStatus()).isEqualTo("CANCELED");
            assertThat(finalOrder.getPayTime()).isNull();
            assertThat(queryStates()).allSatisfy(state -> {
                assertThat(state.getStatus()).isZero();
                assertThat(state.getLockOrderId()).isNull();
            });
            assertThat(showtimeMapper.selectById(context.showtime.getId()).getAvailableSeats())
                    .isEqualTo(2);
        }
    }

    @Test
    void expiredPaymentAndTimeoutCancellationShouldEndCanceledAndAvailable() throws Exception {
        context = insertShowtimeWithSeats(2, LocalDateTime.now().plusHours(2));
        CreateTicketOrderVO created = ticketOrderService.createOrder(
                1L, lockAndBuildRequest(context).request);
        TicketOrder expired = new TicketOrder();
        expired.setId(created.getOrderId());
        expired.setExpireTime(LocalDateTime.now().minusSeconds(1));
        assertThat(ticketOrderMapper.updateById(expired)).isEqualTo(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> payment = executor.submit(() -> runIgnoringBusinessConflict(
                    start, () -> ticketOrderService.payOrder(1L, created.getOrderId())));
            Future<?> timeout = executor.submit(() -> {
                await(start);
                cancellationService.cancelExpiredOrder(created.getOrderId());
            });
            start.countDown();
            payment.get(10, TimeUnit.SECONDS);
            timeout.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        TicketOrder finalOrder = ticketOrderMapper.selectById(created.getOrderId());
        assertThat(finalOrder.getStatus()).isEqualTo("CANCELED");
        assertThat(finalOrder.getPayTime()).isNull();
        assertThat(queryStates()).allSatisfy(state -> {
            assertThat(state.getStatus()).isZero();
            assertThat(state.getLockOrderId()).isNull();
        });
        assertThat(showtimeMapper.selectById(context.showtime.getId()).getAvailableSeats())
                .isEqualTo(2);
    }

    private void runIgnoringBusinessConflict(CountDownLatch start, Runnable action) {
        await(start);
        try {
            action.run();
        } catch (BusinessException exception) {
            assertThat(exception.getErrorCode()).isIn(
                    ErrorCode.ORDER_STATUS_CONFLICT, ErrorCode.ORDER_EXPIRED);
        }
    }

    @Test
    void issuanceShouldBeUniqueIdempotentAndLeaveOrderSeatsAndCounterUnchanged() {
        Long id = createPaidOrderForIssuance();
        TicketOrder before = ticketOrderMapper.selectById(id);
        List<ShowtimeSeat> seats = queryStates();
        Showtime showtime = showtimeMapper.selectById(context.showtime.getId());
        java.util.List<com.movielife.vo.IssueTicketVO> first = ticketOrderService.issueTickets(1L, id);
        assertThat(first).hasSize(2);
        assertThat(first).extracting(com.movielife.vo.IssueTicketVO::getTicketCode).doesNotHaveDuplicates();
        assertThat(first).allSatisfy(ticket -> {
            assertThat(ticket.getTicketCode()).matches("[0-9a-f]{32}");
            assertThat(ticket.getTicketStatus()).isEqualTo(1);
        });
        assertThat(ticketOrderService.issueTickets(1L, id)).isEqualTo(first);
        assertThat(ticketOrderMapper.selectById(id)).isEqualTo(before);
        assertThat(queryStates()).isEqualTo(seats);
        assertThat(showtimeMapper.selectById(context.showtime.getId())).isEqualTo(showtime);
    }

    @Test
    void issuanceShouldCompletePartialTicketsAndPreserveExistingCode() {
        Long id = createPaidOrderForIssuance();
        List<TicketOrderItem> items = issuanceItems(id);
        TicketOrderItem issued = items.get(0);
        issued.setTicketCode(java.util.UUID.randomUUID().toString().replace("-", ""));
        issued.setTicketStatus(1);
        ticketOrderItemMapper.updateById(issued);
        ticketOrderService.issueTickets(1L, id);
        List<TicketOrderItem> after = issuanceItems(id);
        assertThat(after.get(0).getTicketCode()).isEqualTo(issued.getTicketCode());
        assertThat(after).allSatisfy(item -> {
            assertThat(item.getTicketStatus()).isEqualTo(1);
            assertThat(item.getTicketCode()).isNotBlank();
        });
    }

    @Test
    void issuanceShouldRejectInconsistentDetailsBeforeAnyWrite() {
        Long id = createPaidOrderForIssuance();
        TicketOrderItem invalid = issuanceItems(id).get(1);
        invalid.setTicketCode(java.util.UUID.randomUUID().toString());
        ticketOrderItemMapper.updateById(invalid);
        List<TicketOrderItem> before = issuanceItems(id);
        assertThatThrownBy(() -> ticketOrderService.issueTickets(1L, id))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR));
        assertThat(issuanceItems(id)).isEqualTo(before);
    }

    @Test
    void issuanceShouldRejectOtherUsersAndEveryNonPaidState() {
        Long id = createPaidOrderForIssuance();
        assertThatThrownBy(() -> ticketOrderService.issueTickets(2L, id))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ORDER_NOT_FOUND));
        for (String status : Arrays.asList("UNPAID", "CANCELED", "REFUNDED")) {
            TicketOrder update = new TicketOrder();
            update.setId(id);
            update.setStatus(status);
            ticketOrderMapper.updateById(update);
            assertThatThrownBy(() -> ticketOrderService.issueTickets(1L, id))
                    .isInstanceOfSatisfying(BusinessException.class, exception ->
                            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ORDER_STATUS_CONFLICT));
        }
        assertThat(issuanceItems(id)).allSatisfy(item -> assertThat(item.getTicketCode()).isNull());
    }

    @Test
    void concurrentIssuanceShouldReturnSameCodes() throws Exception {
        Long id = createPaidOrderForIssuance();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> first = executor.submit(() -> { await(start); return ticketOrderService.issueTickets(1L, id); });
            Future<?> second = executor.submit(() -> { await(start); return ticketOrderService.issueTickets(1L, id); });
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(second.get(10, TimeUnit.SECONDS));
            assertThat(issuanceItems(id)).extracting(TicketOrderItem::getTicketCode).doesNotHaveDuplicates();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void issuanceBatchUniqueConflictShouldLeaveEveryDetailUnchanged() {
        Long id = createPaidOrderForIssuance();
        List<TicketOrderItem> before = issuanceItems(id);
        List<TicketOrderItem> updates = issuanceItems(id);
        String duplicate = java.util.UUID.randomUUID().toString().replace("-", "");
        updates.forEach(item -> item.setTicketCode(duplicate));
        org.springframework.transaction.support.TransactionTemplate transaction =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.execute(status ->
                ticketOrderItemMapper.issueBatch(id, updates, 0, 1)))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(issuanceItems(id)).isEqualTo(before);
    }

    @Test
    void refundShouldReleaseSoldSeatsRestoreCounterAndPreserveIssuedCodes() {
        Long id = createPaidOrderForIssuance();
        ticketOrderService.issueTickets(1L, id);
        List<TicketOrderItem> beforeItems = issuanceItems(id);
        List<String> codes = beforeItems.stream().map(TicketOrderItem::getTicketCode)
                .collect(Collectors.toList());

        ticketOrderService.refundOrder(1L, id);

        assertThat(ticketOrderMapper.selectById(id).getStatus()).isEqualTo("REFUNDED");
        assertThat(queryStates()).allSatisfy(state -> {
            assertThat(state.getStatus()).isZero();
            assertThat(state.getLockOrderId()).isNull();
        });
        assertThat(showtimeMapper.selectById(context.showtime.getId()).getAvailableSeats()).isEqualTo(2);
        assertThat(issuanceItems(id)).extracting(TicketOrderItem::getTicketCode).containsExactlyElementsOf(codes);
        assertThat(issuanceItems(id)).allSatisfy(item -> assertThat(item.getTicketStatus()).isEqualTo(3));

        ticketOrderService.refundOrder(1L, id);
        assertThat(showtimeMapper.selectById(context.showtime.getId()).getAvailableSeats()).isEqualTo(2);
    }

    @Test
    void refundShouldRejectStartedShowtimeWithoutChangingPaidOrder() {
        Long id = createPaidOrderForIssuance();
        Showtime started = new Showtime();
        started.setId(context.showtime.getId());
        started.setStartTime(LocalDateTime.now().minusSeconds(1));
        showtimeMapper.updateById(started);
        List<ShowtimeSeat> before = queryStates();

        assertThatThrownBy(() -> ticketOrderService.refundOrder(1L, id))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.REFUND_NOT_ALLOWED));

        assertThat(ticketOrderMapper.selectById(id).getStatus()).isEqualTo("PAID");
        assertThat(queryStates()).isEqualTo(before);
        assertThat(showtimeMapper.selectById(context.showtime.getId()).getAvailableSeats()).isZero();
    }

    @Test
    void refundShouldRejectUsedTicketBeforeChangingOrderSeatsOrCounter() {
        Long id = createPaidOrderForIssuance();
        ticketOrderService.issueTickets(1L, id);
        TicketOrderItem used = issuanceItems(id).get(0);
        used.setTicketStatus(2);
        used.setUsedTime(LocalDateTime.now().minusMinutes(1).withNano(0));
        used.setUsedByUserId(1L);
        assertThat(ticketOrderItemMapper.updateById(used)).isEqualTo(1);

        TicketOrder beforeOrder = ticketOrderMapper.selectById(id);
        List<TicketOrderItem> beforeItems = issuanceItems(id);
        List<ShowtimeSeat> beforeSeats = queryStates();
        Integer beforeAvailableSeats = showtimeMapper.selectById(context.showtime.getId()).getAvailableSeats();

        assertThatThrownBy(() -> ticketOrderService.refundOrder(1L, id))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.REFUND_NOT_ALLOWED));

        assertThat(ticketOrderMapper.selectById(id)).isEqualTo(beforeOrder);
        assertThat(issuanceItems(id)).isEqualTo(beforeItems);
        assertThat(queryStates()).isEqualTo(beforeSeats);
        assertThat(showtimeMapper.selectById(context.showtime.getId()).getAvailableSeats())
                .isEqualTo(beforeAvailableSeats);
    }

    @Test
    void verificationShouldUseOneTicketOnlyAndPreserveFirstEvidenceOnRetry() {
        Long id = createPaidOrderForIssuance();
        ticketOrderService.issueTickets(1L, id);
        grantVenueStaff(1L, 1);
        List<TicketOrderItem> beforeItems = issuanceItems(id);
        List<ShowtimeSeat> beforeSeats = queryStates();
        Integer beforeAvailableSeats = showtimeMapper.selectById(context.showtime.getId()).getAvailableSeats();

        TicketVerificationResult result = ticketVerificationService.verifyTicket(
                1L, beforeItems.get(0).getTicketCode());

        assertThat(result.getTicketStatus()).isEqualTo(2);
        assertThat(result.getOrderId()).isEqualTo(id);
        assertThat(result.getShowtimeId()).isEqualTo(context.showtime.getId());
        assertThat(result.getVenueId()).isEqualTo(context.venueId);
        assertThat(result.getHallId()).isEqualTo(context.hallId);
        assertThat(result.getSeatId()).isEqualTo(beforeItems.get(0).getSeatId());
        assertThat(result.getUsedTime()).isNotNull();

        List<TicketOrderItem> afterItems = issuanceItems(id);
        TicketOrderItem used = afterItems.get(0);
        assertThat(used.getTicketStatus()).isEqualTo(2);
        assertThat(used.getUsedTime()).isEqualTo(result.getUsedTime());
        assertThat(used.getUsedByUserId()).isEqualTo(1L);
        assertThat(afterItems.get(1).getTicketStatus()).isEqualTo(1);
        assertThat(afterItems.get(1).getUsedTime()).isNull();
        assertThat(ticketOrderMapper.selectById(id).getStatus()).isEqualTo("PAID");
        assertThat(queryStates()).isEqualTo(beforeSeats);
        assertThat(showtimeMapper.selectById(context.showtime.getId()).getAvailableSeats())
                .isEqualTo(beforeAvailableSeats);

        assertThatThrownBy(() -> ticketVerificationService.verifyTicket(1L, used.getTicketCode()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.TICKET_ALREADY_USED));
        TicketOrderItem retried = issuanceItems(id).get(0);
        assertThat(retried.getUsedTime()).isEqualTo(used.getUsedTime());
        assertThat(retried.getUsedByUserId()).isEqualTo(used.getUsedByUserId());
    }

    @Test
    void verificationShouldRejectInactiveVenueStaffWithoutChangingTicket() {
        Long id = createPaidOrderForIssuance();
        ticketOrderService.issueTickets(1L, id);
        grantVenueStaff(1L, 0);
        List<TicketOrderItem> before = issuanceItems(id);

        assertThatThrownBy(() -> ticketVerificationService.verifyTicket(
                1L, before.get(0).getTicketCode()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.TICKET_VERIFY_FORBIDDEN));

        assertThat(issuanceItems(id)).isEqualTo(before);
        assertThat(ticketOrderMapper.selectById(id).getStatus()).isEqualTo("PAID");
        assertThat(queryStates()).allSatisfy(state -> assertThat(state.getStatus()).isEqualTo(2));
        assertThat(showtimeMapper.selectById(context.showtime.getId()).getAvailableSeats()).isZero();
    }

    @Test
    void concurrentVerificationAndRefundShouldProduceOnlyAValidTerminalState() throws Exception {
        Long id = createPaidOrderForIssuance();
        ticketOrderService.issueTickets(1L, id);
        grantVenueStaff(1L, 1);
        String ticketCode = issuanceItems(id).get(0).getTicketCode();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<String> verification = executor.submit(() -> {
                await(start);
                try {
                    ticketVerificationService.verifyTicket(1L, ticketCode);
                    return "VERIFIED";
                } catch (BusinessException exception) {
                    return exception.getErrorCode().name();
                }
            });
            Future<String> refund = executor.submit(() -> {
                await(start);
                try {
                    ticketOrderService.refundOrder(1L, id);
                    return "REFUNDED";
                } catch (BusinessException exception) {
                    return exception.getErrorCode().name();
                }
            });
            start.countDown();
            String verificationOutcome = verification.get(10, TimeUnit.SECONDS);
            String refundOutcome = refund.get(10, TimeUnit.SECONDS);

            TicketOrder order = ticketOrderMapper.selectById(id);
            List<TicketOrderItem> items = issuanceItems(id);
            if ("REFUNDED".equals(order.getStatus())) {
                assertThat(refundOutcome).isEqualTo("REFUNDED");
                assertThat(verificationOutcome).isEqualTo(ErrorCode.TICKET_REFUNDED.name());
                assertThat(items).allSatisfy(item -> assertThat(item.getTicketStatus()).isEqualTo(3));
                assertThat(queryStates()).allSatisfy(state -> assertThat(state.getStatus()).isZero());
                assertThat(showtimeMapper.selectById(context.showtime.getId()).getAvailableSeats()).isEqualTo(2);
            } else {
                assertThat(order.getStatus()).isEqualTo("PAID");
                assertThat(verificationOutcome).isEqualTo("VERIFIED");
                assertThat(refundOutcome).isEqualTo(ErrorCode.REFUND_NOT_ALLOWED.name());
                assertThat(items).extracting(TicketOrderItem::getTicketStatus).containsExactly(2, 1);
                assertThat(queryStates()).allSatisfy(state -> assertThat(state.getStatus()).isEqualTo(2));
                assertThat(showtimeMapper.selectById(context.showtime.getId()).getAvailableSeats()).isZero();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void refundSeatOrCounterMismatchShouldRollbackEveryChange() {
        Long id = createPaidOrderForIssuance();
        ShowtimeSeat inconsistent = queryStates().get(1);
        inconsistent.setStatus(0);
        inconsistent.setLockOrderId(null);
        showtimeSeatMapper.updateById(inconsistent);

        assertThatThrownBy(() -> ticketOrderService.refundOrder(1L, id))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR));
        assertThat(ticketOrderMapper.selectById(id).getStatus()).isEqualTo("PAID");
        assertThat(queryStates().get(0).getStatus()).isEqualTo(2);
        assertThat(showtimeMapper.selectById(context.showtime.getId()).getAvailableSeats()).isZero();
    }

    @Test
    void concurrentRefundShouldRestoreResourcesOnlyOnce() throws Exception {
        Long id = createPaidOrderForIssuance();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> first = executor.submit(() -> { await(start); ticketOrderService.refundOrder(1L, id); });
            Future<?> second = executor.submit(() -> { await(start); ticketOrderService.refundOrder(1L, id); });
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(ticketOrderMapper.selectById(id).getStatus()).isEqualTo("REFUNDED");
        assertThat(showtimeMapper.selectById(context.showtime.getId()).getAvailableSeats()).isEqualTo(2);
        assertThat(queryStates()).allSatisfy(state -> {
            assertThat(state.getStatus()).isZero();
            assertThat(state.getLockOrderId()).isNull();
        });
    }

    private Long createPaidOrderForIssuance() {
        context = insertShowtimeWithSeats(2, LocalDateTime.now().plusHours(2));
        Long id = ticketOrderService.createOrder(1L, lockAndBuildRequest(context).request).getOrderId();
        ticketOrderService.payOrder(1L, id);
        return id;
    }

    private void grantVenueStaff(Long userId, int status) {
        VenueStaff staff = new VenueStaff();
        staff.setUserId(userId);
        staff.setVenueId(context.venueId);
        staff.setStatus(status);
        venueStaffMapper.insert(staff);
    }

    private List<TicketOrderItem> issuanceItems(Long id) {
        return ticketOrderItemMapper.selectList(new LambdaQueryWrapper<TicketOrderItem>()
                .eq(TicketOrderItem::getOrderId, id).orderByAsc(TicketOrderItem::getSeatId));
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
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
