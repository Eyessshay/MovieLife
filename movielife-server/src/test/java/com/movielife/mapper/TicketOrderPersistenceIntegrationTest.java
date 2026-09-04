package com.movielife.mapper;

import com.movielife.entity.Seat;
import com.movielife.entity.Showtime;
import com.movielife.entity.TicketOrder;
import com.movielife.entity.TicketOrderItem;
import com.movielife.entity.User;
import com.movielife.enums.TicketOrderStatus;
import com.movielife.enums.TicketStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "movielife.startup-check.enabled=false",
                "spring.autoconfigure.exclude="
                        + "org.redisson.spring.starter.RedissonAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
        }
)
@Transactional
class TicketOrderPersistenceIntegrationTest {

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private UserMapper userMapper;
    @Autowired
    private ShowtimeMapper showtimeMapper;
    @Autowired
    private SeatMapper seatMapper;
    @Autowired
    private TicketOrderMapper ticketOrderMapper;
    @Autowired
    private TicketOrderItemMapper ticketOrderItemMapper;

    @Test
    void shouldInsertAndReadCompleteOrderAndItemMapping() {
        User user = userMapper.selectById(1L);
        Showtime showtime = showtimeMapper.selectById(1L);
        Seat seat = seatMapper.selectById(1L);
        assertThat(user).isNotNull();
        assertThat(showtime).isNotNull();
        assertThat(seat).isNotNull();

        LocalDateTime expireTime = LocalDateTime.now().plusMinutes(15).withNano(0);
        String lockToken = UUID.randomUUID().toString().replace("-", "");

        TicketOrder order = new TicketOrder();
        order.setUserId(user.getId());
        order.setShowtimeId(showtime.getId());
        order.setLockToken(lockToken);
        order.setTotalAmount(new BigDecimal("68.50"));
        order.setStatus(TicketOrderStatus.UNPAID.getDbValue());
        order.setExpireTime(expireTime);

        assertThat(ticketOrderMapper.insert(order)).isEqualTo(1);
        assertThat(order.getId()).isPositive();

        TicketOrderItem item = new TicketOrderItem();
        item.setOrderId(order.getId());
        item.setSeatId(seat.getId());
        item.setPrice(new BigDecimal("68.50"));
        item.setTicketStatus(TicketStatus.PENDING.getCode());

        assertThat(ticketOrderItemMapper.insert(item)).isEqualTo(1);
        assertThat(item.getId()).isPositive();

        TicketOrder persistedOrder = ticketOrderMapper.selectById(order.getId());
        assertThat(persistedOrder.getUserId()).isEqualTo(user.getId());
        assertThat(persistedOrder.getShowtimeId()).isEqualTo(showtime.getId());
        assertThat(persistedOrder.getLockToken()).isEqualTo(lockToken);
        assertThat(persistedOrder.getTotalAmount()).isEqualByComparingTo("68.50");
        assertThat(persistedOrder.getStatus()).isEqualTo("UNPAID");
        assertThat(persistedOrder.getExpireTime()).isEqualTo(expireTime);
        assertThat(persistedOrder.getCreateTime()).isNotNull();
        assertThat(persistedOrder.getPayTime()).isNull();

        TicketOrderItem persistedItem = ticketOrderItemMapper.selectById(item.getId());
        assertThat(persistedItem.getOrderId()).isEqualTo(order.getId());
        assertThat(persistedItem.getSeatId()).isEqualTo(seat.getId());
        assertThat(persistedItem.getPrice()).isEqualByComparingTo("68.50");
        assertThat(persistedItem.getTicketCode()).isNull();
        assertThat(persistedItem.getTicketStatus()).isEqualTo(0);
    }
}
