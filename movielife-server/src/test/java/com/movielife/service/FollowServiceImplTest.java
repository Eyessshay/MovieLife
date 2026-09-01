package com.movielife.service;

import com.movielife.entity.User;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.mapper.FollowMapper;
import com.movielife.mapper.UserMapper;
import com.movielife.service.impl.FollowServiceImpl;
import com.movielife.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FollowServiceImplTest {

    @Mock private FollowMapper followMapper;
    @Mock private UserMapper userMapper;

    private FollowServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new FollowServiceImpl(followMapper, userMapper);
        UserHolder.saveUser(userContext(1L));
    }

    @AfterEach
    void cleanup() {
        UserHolder.removeUser();
    }

    @Test
    void cannotFollowSelf() {
        assertThatThrownBy(() -> service.follow(1L))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.CANNOT_FOLLOW_SELF);
    }

    @Test
    void repeatedFollowShouldBeIdempotent() {
        User target = new User();
        target.setId(2L);
        when(userMapper.selectById(2L)).thenReturn(target);
        when(followMapper.selectCount(any())).thenReturn(1L);

        service.follow(2L);

        verify(followMapper, never()).insert(any());
    }

    private com.movielife.dto.UserDTO userContext(Long id) {
        com.movielife.dto.UserDTO user = new com.movielife.dto.UserDTO();
        user.setId(id);
        return user;
    }
}
