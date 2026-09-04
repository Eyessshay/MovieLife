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
import com.movielife.enums.TicketOrderStatus;
import com.movielife.enums.TicketStatus;
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
import com.movielife.vo.SeatLockVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "movielife.startup-check.enabled=false")
@Transactional
class TicketOrderCreationValidationIntegrationTest {

    @Autowired
    private TicketOrderCreationValidator validator;
    @Autowired
    private SeatLockService seatLockService;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private MovieMapper movieMapper;
    @Autowired
    private VenueMapper venueMapper;
    @Autowired
    private HallMapper hallMapper;
    @Autowired
    private SeatMapper seatMapper;
    @Autowired
    private ShowtimeMapper showtimeMapper;
    @Autowired
    private ShowtimeSeatMapper showtimeSeatMapper;
    @Autowired
    private TicketOrderMapper ticketOrderMapper;
    @Autowired
    private TicketOrderItemMapper ticketOrderItemMapper;

    private final Set<String> cleanupKeys = new LinkedHashSet<>();

    @BeforeEach
    void setUpUser() {
        User user = userMapper.selectById(1L);
        assertThat(user).isNotNull();
        UserDTO userDTO = new UserDTO();
        userDTO.setId(user.getId());
        UserHolder.saveUser(userDTO);
    }

    @AfterEach
    void cleanRedisAndUserContext() {
        UserHolder.removeUser();
        if (!cleanupKeys.isEmpty()) {
            redisTemplate.delete(cleanupKeys);
            assertThat(redisTemplate.opsForValue().multiGet(cleanupKeys))
                    .allMatch(value -> value == null);
        }
        cleanupKeys.clear();
    }

    @Test
    void unconsumedTokenValidationShouldOnlyReadRedisAndLeaveMySqlUnchanged() {
        TestContext testContext = insertFutureShowtimeWithSeats();
        int availableSeatsBefore = testContext.showtime.getAvailableSeats();
        List<Integer> seatStatusesBefore = querySeatStatuses(testContext);

        SeatLockRequest lockRequest = new SeatLockRequest();
        lockRequest.setSeatIds(testContext.seatIds);
        SeatLockVO lock = seatLockService.lock(testContext.showtime.getId(), lockRequest);
        registerLockKeys(testContext.showtime.getId(), testContext.seatIds, lock.getLockToken());

        String firstSeatKey = seatKey(testContext.showtime.getId(), testContext.seatIds.get(0));
        String metadataKey = tokenKey(lock.getLockToken());
        Long seatTtlBefore = redisTemplate.getExpire(firstSeatKey, TimeUnit.MILLISECONDS);
        Long metadataTtlBefore = redisTemplate.getExpire(metadataKey, TimeUnit.MILLISECONDS);

        CreateTicketOrderDTO dto = request(
                testContext.showtime.getId(),
                lock.getLockToken(),
                testContext.seatIds.get(1),
                testContext.seatIds.get(0)
        );
        TicketOrderCreationContext result = validator.validate(1L, dto);

        assertThat(result.isConsumed()).isFalse();
        assertThat(result.getSeatIds()).containsExactlyElementsOf(testContext.seatIds);
        assertThat(result.getLockMetadata()).isNotNull();
        assertThat(ticketOrderMapper.selectCount(
                new LambdaQueryWrapper<TicketOrder>()
                        .eq(TicketOrder::getLockToken, lock.getLockToken())))
                .isZero();
        assertThat(showtimeMapper.selectById(testContext.showtime.getId()).getAvailableSeats())
                .isEqualTo(availableSeatsBefore);
        assertThat(querySeatStatuses(testContext)).isEqualTo(seatStatusesBefore);
        assertThat(showtimeSeatMapper.selectList(
                new LambdaQueryWrapper<ShowtimeSeat>()
                        .eq(ShowtimeSeat::getShowtimeId, testContext.showtime.getId())))
                .allMatch(state -> state.getLockOrderId() == null);

        assertThat(redisTemplate.opsForValue().get(metadataKey)).isNotNull();
        assertThat(redisTemplate.opsForValue().get(firstSeatKey)).isEqualTo(lock.getLockToken());
        assertThat(redisTemplate.getExpire(firstSeatKey, TimeUnit.MILLISECONDS))
                .isPositive()
                .isLessThanOrEqualTo(seatTtlBefore + 100L);
        assertThat(redisTemplate.getExpire(metadataKey, TimeUnit.MILLISECONDS))
                .isPositive()
                .isLessThanOrEqualTo(metadataTtlBefore + 100L);
    }

    @Test
    void consumedTokenShouldUseMySqlAfterRedisKeysAreGone() {
        TestContext testContext = insertFutureShowtimeWithSeats();
        String lockToken = UUID.randomUUID().toString().replace("-", "");

        TicketOrder order = new TicketOrder();
        order.setUserId(1L);
        order.setShowtimeId(testContext.showtime.getId());
        order.setLockToken(lockToken);
        order.setTotalAmount(new BigDecimal("90.00"));
        order.setStatus(TicketOrderStatus.PAID.getDbValue());
        order.setExpireTime(LocalDateTime.now().plusMinutes(15).withNano(0));
        order.setPayTime(LocalDateTime.now().withNano(0));
        assertThat(ticketOrderMapper.insert(order)).isEqualTo(1);

        insertOrderItem(order.getId(), testContext.seatIds.get(0), "45.00");
        insertOrderItem(order.getId(), testContext.seatIds.get(1), "45.00");
        redisTemplate.delete(Arrays.asList(
                tokenKey(lockToken),
                seatKey(testContext.showtime.getId(), testContext.seatIds.get(0)),
                seatKey(testContext.showtime.getId(), testContext.seatIds.get(1))
        ));

        TicketOrderCreationContext result = validator.validate(
                1L,
                request(
                        testContext.showtime.getId(),
                        lockToken,
                        testContext.seatIds.get(1),
                        testContext.seatIds.get(0)
                )
        );

        assertThat(result.isConsumed()).isTrue();
        assertThat(result.getExistingOrder().getId()).isEqualTo(order.getId());
        assertThat(result.getExistingOrder().getStatus()).isEqualTo("PAID");
        assertThat(result.getSeatIds()).containsExactlyElementsOf(testContext.seatIds);
        assertThat(result.getLockMetadata()).isNull();
    }

    private TestContext insertFutureShowtimeWithSeats() {
        Movie movie = new Movie();
        movie.setTitle("订单校验联调电影");
        movie.setAvgScore(BigDecimal.ZERO);
        movie.setScoreCount(0);
        movie.setStatus(1);
        movieMapper.insert(movie);

        Venue venue = new Venue();
        venue.setName("订单校验联调影院");
        venue.setType("cinema");
        venue.setCity("上海");
        venue.setAddress("订单校验联调地址");
        venue.setAvgScore(BigDecimal.ZERO);
        venue.setStatus(1);
        venueMapper.insert(venue);

        Hall hall = new Hall();
        hall.setVenueId(venue.getId());
        hall.setName("订单校验联调厅");
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

        LocalDateTime startTime = LocalDateTime.now().plusDays(1).withNano(0);
        Showtime showtime = new Showtime();
        showtime.setBizType("MOVIE");
        showtime.setBizId(movie.getId());
        showtime.setVenueId(venue.getId());
        showtime.setHallId(hall.getId());
        showtime.setStartTime(startTime);
        showtime.setEndTime(startTime.plusHours(2));
        showtime.setBasePrice(new BigDecimal("45.00"));
        showtime.setTotalSeats(seatIds.size());
        showtime.setAvailableSeats(seatIds.size());
        showtime.setStatus(1);
        showtimeMapper.insert(showtime);

        for (Long seatId : seatIds) {
            ShowtimeSeat state = new ShowtimeSeat();
            state.setShowtimeId(showtime.getId());
            state.setSeatId(seatId);
            state.setStatus(0);
            state.setPrice(new BigDecimal("45.00"));
            state.setVersion(0);
            showtimeSeatMapper.insert(state);
        }
        return new TestContext(showtime, seatIds);
    }

    private void insertOrderItem(Long orderId, Long seatId, String price) {
        TicketOrderItem item = new TicketOrderItem();
        item.setOrderId(orderId);
        item.setSeatId(seatId);
        item.setPrice(new BigDecimal(price));
        item.setTicketStatus(TicketStatus.PENDING.getCode());
        assertThat(ticketOrderItemMapper.insert(item)).isEqualTo(1);
    }

    private List<Integer> querySeatStatuses(TestContext context) {
        return showtimeSeatMapper.selectList(
                        new LambdaQueryWrapper<ShowtimeSeat>()
                                .eq(ShowtimeSeat::getShowtimeId, context.showtime.getId())
                                .orderByAsc(ShowtimeSeat::getSeatId))
                .stream()
                .map(ShowtimeSeat::getStatus)
                .collect(java.util.stream.Collectors.toList());
    }

    private void registerLockKeys(Long showtimeId, List<Long> seatIds, String lockToken) {
        cleanupKeys.add(tokenKey(lockToken));
        for (Long seatId : seatIds) {
            cleanupKeys.add(seatKey(showtimeId, seatId));
        }
    }

    private CreateTicketOrderDTO request(Long showtimeId,
                                         String lockToken,
                                         Long... seatIds) {
        CreateTicketOrderDTO dto = new CreateTicketOrderDTO();
        dto.setShowtimeId(showtimeId);
        dto.setSeatIds(Arrays.asList(seatIds));
        dto.setLockToken(lockToken);
        return dto;
    }

    private String seatKey(Long showtimeId, Long seatId) {
        return RedisConstants.SEAT_LOCK_KEY_PREFIX + showtimeId + ":" + seatId;
    }

    private String tokenKey(String lockToken) {
        return RedisConstants.SEAT_LOCK_TOKEN_KEY_PREFIX + lockToken;
    }

    private static final class TestContext {

        private final Showtime showtime;
        private final List<Long> seatIds;

        private TestContext(Showtime showtime, List<Long> seatIds) {
            this.showtime = showtime;
            this.seatIds = seatIds;
        }
    }
}
