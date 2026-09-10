package com.movielife.service;

import com.movielife.dto.ActivityQueryDTO;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
class ActivityServiceIntegrationTest {

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @Autowired private ActivityService activityService;
    @Autowired private ActivityMapper activityMapper;
    @Autowired private VenueMapper venueMapper;
    @Autowired private HallMapper hallMapper;
    @Autowired private ShowtimeMapper showtimeMapper;

    @Test
    void shouldFilterActivityListAndHideInactiveDetails() {
        Activity target = insertActivity("Step102唯一活动", "drama", "杭州", 1);
        insertActivity("Step102其他活动", "talkshow", "上海", 1);
        Activity hidden = insertActivity("Step102隐藏活动", "drama", "杭州", 0);

        ActivityQueryDTO query = new ActivityQueryDTO();
        query.setCity("杭州");
        query.setType("drama");
        query.setKeyword("唯一");

        assertThat(activityService.queryActivities(query).getList())
                .extracting("id").containsExactly(target.getId());
        assertThat(activityService.queryActivityDetail(target.getId()).getTitle())
                .isEqualTo("Step102唯一活动");
        assertThatThrownBy(() -> activityService.queryActivityDetail(hidden.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.ACTIVITY_NOT_FOUND);
    }

    @Test
    void shouldReturnOnlyFutureActivityShowtimesForSelectedDateAndVenue() {
        Activity activity = insertActivity("Step102场次活动", "drama", "上海", 1);
        Venue venue = insertVenue("Step102有效剧场", 1);
        Venue disabled = insertVenue("Step102停用剧场", 0);
        Hall hall = insertHall(venue, "Step102主厅");
        Hall disabledHall = insertHall(disabled, "Step102停用厅");
        LocalDate date = LocalDate.now().plusDays(2);
        Showtime morning = insertShowtime(activity, venue, hall, date.atTime(10, 0), 1);
        Showtime evening = insertShowtime(activity, venue, hall, date.atTime(19, 0), 1);
        insertShowtime(activity, venue, hall, date.atTime(12, 0), 0);
        insertShowtime(activity, disabled, disabledHall, date.atTime(14, 0), 1);
        insertShowtime(activity, venue, hall, date.plusDays(1).atTime(10, 0), 1);

        ActivityShowtimeQueryDTO query = new ActivityShowtimeQueryDTO();
        query.setDate(date);
        query.setVenueId(venue.getId());
        List<ActivityShowtimeVO> result = activityService.queryActivityShowtimes(
                activity.getId(), query);

        assertThat(result).extracting(ActivityShowtimeVO::getShowtimeId)
                .containsExactly(morning.getId(), evening.getId());
        assertThat(result).extracting(ActivityShowtimeVO::getVenueName)
                .containsOnly("Step102有效剧场");
    }

    private Activity insertActivity(String title, String type, String city, int status) {
        Activity value = new Activity();
        value.setTitle(title);
        value.setType(type);
        value.setCity(city);
        value.setDescription("集成测试活动");
        value.setPriceMin(new BigDecimal("100.00"));
        value.setPriceMax(new BigDecimal("200.00"));
        value.setStatus(status);
        activityMapper.insert(value);
        return value;
    }

    private Venue insertVenue(String name, int status) {
        Venue value = new Venue();
        value.setName(name);
        value.setType("theater");
        value.setCity("上海");
        value.setAddress("Step 10.2 测试地址");
        value.setAvgScore(BigDecimal.ZERO);
        value.setStatus(status);
        venueMapper.insert(value);
        return value;
    }

    private Hall insertHall(Venue venue, String name) {
        Hall value = new Hall();
        value.setVenueId(venue.getId());
        value.setName(name);
        hallMapper.insert(value);
        return value;
    }

    private Showtime insertShowtime(Activity activity, Venue venue, Hall hall,
                                    LocalDateTime startTime, int status) {
        Showtime value = new Showtime();
        value.setBizType("ACTIVITY");
        value.setBizId(activity.getId());
        value.setVenueId(venue.getId());
        value.setHallId(hall.getId());
        value.setStartTime(startTime);
        value.setEndTime(startTime.plusHours(2));
        value.setBasePrice(new BigDecimal("100.00"));
        value.setTotalSeats(10);
        value.setAvailableSeats(10);
        value.setStatus(status);
        showtimeMapper.insert(value);
        return value;
    }
}
