package com.movielife.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.movielife.constants.RedisConstants;
import com.movielife.dto.LoginDTO;
import com.movielife.dto.SendCodeDTO;
import com.movielife.dto.UserDTO;
import com.movielife.entity.User;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.mapper.UserMapper;
import com.movielife.service.UserService;
import com.movielife.utils.UserHolder;
import com.movielife.vo.LoginVO;
import com.movielife.vo.UserMeVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private static final DefaultRedisScript<Long> CONSUME_CODE_SCRIPT = new DefaultRedisScript<>(
            "local value = redis.call('get', KEYS[1]); " +
                    "if not value then return -1; end; " +
                    "if value ~= ARGV[1] then return 0; end; " +
                    "redis.call('del', KEYS[1]); return 1;",
            Long.class
    );

    private final UserMapper userMapper;
    private final StringRedisTemplate stringRedisTemplate;

    @Value("${movielife.auth.log-code-enabled:false}")
    private boolean logLoginCodeEnabled;

    @Override
    public void sendCode(SendCodeDTO request) {
        String phone = request.getPhone();
        String limitKey = RedisConstants.LOGIN_CODE_LIMIT_KEY + phone;
        Boolean allowed = stringRedisTemplate.opsForValue().setIfAbsent(
                limitKey,
                "1",
                RedisConstants.LOGIN_CODE_LIMIT_TTL
        );
        if (!Boolean.TRUE.equals(allowed)) {
            throw new BusinessException(ErrorCode.CODE_SEND_TOO_FREQUENT);
        }

        String code = RandomUtil.randomNumbers(6);
        try {
            stringRedisTemplate.opsForValue().set(
                    RedisConstants.LOGIN_CODE_KEY + phone,
                    code,
                    RedisConstants.LOGIN_CODE_TTL
            );
        } catch (RuntimeException exception) {
            stringRedisTemplate.delete(limitKey);
            throw new BusinessException(ErrorCode.CODE_SEND_FAILED);
        }

        if (logLoginCodeEnabled) {
            log.info("Development login code generated: phone={}****{} code={}",
                    phone.substring(0, 3), phone.substring(7), code);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LoginVO login(LoginDTO request) {
        String codeKey = RedisConstants.LOGIN_CODE_KEY + request.getPhone();
        Long result = stringRedisTemplate.execute(
                CONSUME_CODE_SCRIPT,
                Collections.singletonList(codeKey),
                request.getCode()
        );
        if (result == null || result == -1L) {
            throw new BusinessException(ErrorCode.CODE_EXPIRED);
        }
        if (result == 0L) {
            throw new BusinessException(ErrorCode.CODE_INVALID);
        }

        User user = findByPhone(request.getPhone());
        if (user == null) {
            user = createUser(request.getPhone());
        }

        String token = IdUtil.fastSimpleUUID();
        stringRedisTemplate.opsForValue().set(
                RedisConstants.LOGIN_TOKEN_KEY + token,
                String.valueOf(user.getId()),
                RedisConstants.LOGIN_TOKEN_TTL
        );

        return new LoginVO(token, RedisConstants.LOGIN_TOKEN_TTL.getSeconds(), toUserDTO(user));
    }

    @Override
    public UserMeVO getCurrentUser() {
        Long userId = UserHolder.getUserId();
        User user = userId == null ? null : userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        return toUserMeVO(user);
    }

    @Override
    public void logout(String token) {
        stringRedisTemplate.delete(RedisConstants.LOGIN_TOKEN_KEY + token);
    }

    private User findByPhone(String phone) {
        return userMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getPhone, phone));
    }

    private User createUser(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName("影迷_" + RandomUtil.randomString(8));
        user.setGender(0);
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
        userMapper.insert(user);
        return user;
    }

    private UserDTO toUserDTO(User user) {
        UserDTO dto = new UserDTO();
        dto.setId(user.getId());
        dto.setNickName(user.getNickName());
        dto.setIcon(user.getIcon());
        dto.setCity(user.getCity());
        return dto;
    }

    private UserMeVO toUserMeVO(User user) {
        UserMeVO vo = new UserMeVO();
        vo.setId(user.getId());
        vo.setNickName(user.getNickName());
        vo.setIcon(user.getIcon());
        vo.setGender(user.getGender());
        vo.setCity(user.getCity());
        vo.setBirthday(user.getBirthday());
        return vo;
    }
}
