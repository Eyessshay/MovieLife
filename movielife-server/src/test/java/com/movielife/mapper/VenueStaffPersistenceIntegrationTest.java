package com.movielife.mapper;

import com.movielife.entity.User;
import com.movielife.entity.Venue;
import com.movielife.entity.VenueStaff;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

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
class VenueStaffPersistenceIntegrationTest {

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private UserMapper userMapper;
    @Autowired
    private VenueMapper venueMapper;
    @Autowired
    private VenueStaffMapper venueStaffMapper;

    @Test
    void shouldPersistVenueStaffAndRejectDuplicateRelationship() {
        User user = insertUser();
        Venue venue = insertVenue();

        VenueStaff staff = new VenueStaff();
        staff.setUserId(user.getId());
        staff.setVenueId(venue.getId());
        staff.setStatus(1);
        assertThat(venueStaffMapper.insert(staff)).isEqualTo(1);

        VenueStaff persisted = venueStaffMapper.selectById(staff.getId());
        assertThat(persisted.getUserId()).isEqualTo(user.getId());
        assertThat(persisted.getVenueId()).isEqualTo(venue.getId());
        assertThat(persisted.getStatus()).isEqualTo(1);
        assertThat(persisted.getCreateTime()).isNotNull();
        assertThat(persisted.getUpdateTime()).isNotNull();

        VenueStaff duplicate = new VenueStaff();
        duplicate.setUserId(user.getId());
        duplicate.setVenueId(venue.getId());
        duplicate.setStatus(0);
        assertThatThrownBy(() -> venueStaffMapper.insert(duplicate))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void shouldRejectUnknownVenueStaffStatus() {
        User user = insertUser();
        Venue venue = insertVenue();
        VenueStaff staff = new VenueStaff();
        staff.setUserId(user.getId());
        staff.setVenueId(venue.getId());
        staff.setStatus(2);

        assertThatThrownBy(() -> venueStaffMapper.insert(staff))
                .isInstanceOf(DataAccessException.class);
    }

    private User insertUser() {
        User user = new User();
        user.setPhone("19" + UUID.randomUUID().toString().replace("-", "").substring(0, 9));
        user.setNickName("核销人员映射测试");
        userMapper.insert(user);
        return user;
    }

    private Venue insertVenue() {
        Venue venue = new Venue();
        venue.setName("核销场馆映射测试");
        venue.setType("cinema");
        venue.setCity("上海");
        venue.setAddress("核销场馆映射测试地址");
        venue.setAvgScore(BigDecimal.ZERO);
        venue.setStatus(1);
        venueMapper.insert(venue);
        return venue;
    }
}
