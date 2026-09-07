package com.movielife.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.movielife.constants.RedisConstants;
import com.movielife.entity.Seat;
import com.movielife.entity.Showtime;
import com.movielife.entity.ShowtimeSeat;
import com.movielife.entity.TicketOrder;
import com.movielife.entity.TicketOrderItem;
import com.movielife.mapper.SeatMapper;
import com.movielife.mapper.ShowtimeMapper;
import com.movielife.mapper.ShowtimeSeatMapper;
import com.movielife.mapper.TicketOrderItemMapper;
import com.movielife.mapper.TicketOrderMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "movielife.startup-check.enabled=false")
class TicketOrderTimeoutJobIntegrationTest {

    @Autowired private TicketOrderTimeoutJob job;
    @Autowired private TicketOrderMapper ticketOrderMapper;
    @Autowired private TicketOrderItemMapper ticketOrderItemMapper;
    @Autowired private ShowtimeMapper showtimeMapper;
    @Autowired private ShowtimeSeatMapper showtimeSeatMapper;
    @Autowired private SeatMapper seatMapper;
    @Autowired private StringRedisTemplate redisTemplate;

    private final List<Long> orderIds = new ArrayList<>();
    private Long showtimeId;
    private List<Long> seatIds;

    @BeforeEach
    void setUp() {
        List<Seat> seats = seatMapper.selectList(new LambdaQueryWrapper<Seat>()
                .eq(Seat::getHallId, 1L)
                .eq(Seat::getBaseStatus, 1)
                .orderByAsc(Seat::getId)
                .last("LIMIT 4"));
        assertThat(seats).hasSize(4);
        seatIds = new ArrayList<>();
        for (Seat seat : seats) {
            seatIds.add(seat.getId());
        }

        Showtime showtime = new Showtime();
        showtime.setBizType("MOVIE");
        showtime.setBizId(1L);
        showtime.setVenueId(1L);
        showtime.setHallId(1L);
        showtime.setStartTime(LocalDateTime.now().plusDays(1));
        showtime.setEndTime(showtime.getStartTime().plusHours(2));
        showtime.setBasePrice(new BigDecimal("40.00"));
        showtime.setTotalSeats(seatIds.size());
        showtime.setAvailableSeats(0);
        showtime.setStatus(1);
        assertThat(showtimeMapper.insert(showtime)).isEqualTo(1);
        showtimeId = showtime.getId();
    }

    @AfterEach
    void cleanUp() {
        for (Long orderId : orderIds) {
            ticketOrderItemMapper.delete(new LambdaQueryWrapper<TicketOrderItem>()
                    .eq(TicketOrderItem::getOrderId, orderId));
        }
        if (!orderIds.isEmpty()) {
            ticketOrderMapper.deleteBatchIds(orderIds);
        }
        if (showtimeId != null) {
            showtimeSeatMapper.delete(new LambdaQueryWrapper<ShowtimeSeat>()
                    .eq(ShowtimeSeat::getShowtimeId, showtimeId));
            showtimeMapper.deleteById(showtimeId);
        }
    }

    @Test
    void batchShouldCancelOnlyExpiredUnpaidOrdersInStableScan() {
        LocalDateTime now = LocalDateTime.now();
        Long expiredUnpaid = insertOrderWithSeat("UNPAID", now.minusMinutes(5), 0, 1, true);
        Long futureUnpaid = insertOrderWithSeat("UNPAID", now.plusMinutes(5), 1, 1, true);
        Long canceled = insertOrderWithSeat("CANCELED", now.minusMinutes(4), 2, 0, false);
        Long paid = insertOrderWithSeat("PAID", now.minusMinutes(3), 3, 2, true);
        setAvailableSeats(1);
        Set<String> redisKeysBefore = seatLockKeys();

        job.cancelExpiredOrders();

        assertThat(status(expiredUnpaid)).isEqualTo("CANCELED");
        assertThat(status(futureUnpaid)).isEqualTo("UNPAID");
        assertThat(status(canceled)).isEqualTo("CANCELED");
        assertThat(status(paid)).isEqualTo("PAID");
        assertThat(state(0).getStatus()).isZero();
        assertThat(state(0).getLockOrderId()).isNull();
        assertThat(state(1).getStatus()).isEqualTo(1);
        assertThat(state(1).getLockOrderId()).isEqualTo(futureUnpaid);
        assertThat(state(2).getStatus()).isZero();
        assertThat(state(3).getStatus()).isEqualTo(2);
        assertThat(showtimeMapper.selectById(showtimeId).getAvailableSeats()).isEqualTo(2);
        assertThat(seatLockKeys()).isEqualTo(redisKeysBefore);
    }

    @Test
    void brokenExpiredOrderShouldRollbackWithoutBlockingNextOrder() {
        LocalDateTime now = LocalDateTime.now();
        Long broken = insertOrderWithSeat("UNPAID", now.minusMinutes(5), 0, 0, false);
        Long valid = insertOrderWithSeat("UNPAID", now.minusMinutes(4), 1, 1, true);
        setAvailableSeats(1);
        Set<String> redisKeysBefore = seatLockKeys();

        job.cancelExpiredOrders();

        assertThat(status(broken)).isEqualTo("UNPAID");
        assertThat(status(valid)).isEqualTo("CANCELED");
        assertThat(state(0).getStatus()).isZero();
        assertThat(state(1).getStatus()).isZero();
        assertThat(state(1).getLockOrderId()).isNull();
        assertThat(showtimeMapper.selectById(showtimeId).getAvailableSeats()).isEqualTo(2);
        assertThat(seatLockKeys()).isEqualTo(redisKeysBefore);
    }

    private Long insertOrderWithSeat(String status, LocalDateTime expireTime,
                                     int seatIndex, int seatStatus, boolean bindOrder) {
        TicketOrder order = new TicketOrder();
        order.setUserId(1L);
        order.setShowtimeId(showtimeId);
        order.setLockToken("timeout-" + UUID.randomUUID());
        order.setTotalAmount(new BigDecimal("40.00"));
        order.setStatus(status);
        order.setExpireTime(expireTime);
        order.setCreateTime(LocalDateTime.now().minusMinutes(10 - seatIndex));
        assertThat(ticketOrderMapper.insert(order)).isEqualTo(1);
        orderIds.add(order.getId());

        TicketOrderItem item = new TicketOrderItem();
        item.setOrderId(order.getId());
        item.setSeatId(seatIds.get(seatIndex));
        item.setPrice(new BigDecimal("40.00"));
        item.setTicketStatus(0);
        assertThat(ticketOrderItemMapper.insert(item)).isEqualTo(1);

        ShowtimeSeat state = new ShowtimeSeat();
        state.setShowtimeId(showtimeId);
        state.setSeatId(seatIds.get(seatIndex));
        state.setStatus(seatStatus);
        state.setPrice(new BigDecimal("40.00"));
        state.setLockOrderId(bindOrder ? order.getId() : null);
        state.setVersion(0);
        assertThat(showtimeSeatMapper.insert(state)).isEqualTo(1);
        return order.getId();
    }

    private void setAvailableSeats(int availableSeats) {
        Showtime update = new Showtime();
        update.setId(showtimeId);
        update.setAvailableSeats(availableSeats);
        assertThat(showtimeMapper.updateById(update)).isEqualTo(1);
    }

    private String status(Long orderId) {
        return ticketOrderMapper.selectById(orderId).getStatus();
    }

    private ShowtimeSeat state(int seatIndex) {
        return showtimeSeatMapper.selectOne(new LambdaQueryWrapper<ShowtimeSeat>()
                .eq(ShowtimeSeat::getShowtimeId, showtimeId)
                .eq(ShowtimeSeat::getSeatId, seatIds.get(seatIndex)));
    }

    private Set<String> seatLockKeys() {
        Set<String> keys = redisTemplate.keys(RedisConstants.SEAT_LOCK_KEY_PREFIX + "*");
        return keys == null ? new LinkedHashSet<>() : new LinkedHashSet<>(keys);
    }
}
