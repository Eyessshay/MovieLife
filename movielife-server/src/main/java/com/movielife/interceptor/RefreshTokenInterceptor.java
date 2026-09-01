package com.movielife.interceptor;

import com.movielife.constants.RedisConstants;
import com.movielife.dto.UserDTO;
import com.movielife.utils.UserHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Component
@RequiredArgsConstructor
public class RefreshTokenInterceptor implements HandlerInterceptor {

    public static final String TOKEN_INVALID_ATTRIBUTE = "movielife.tokenInvalid";

    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String token = resolveBearerToken(request);
        if (!StringUtils.hasText(token)) {
            return true;
        }

        String userId = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_TOKEN_KEY + token);
        if (!StringUtils.hasText(userId)) {
            request.setAttribute(TOKEN_INVALID_ATTRIBUTE, Boolean.TRUE);
            return true;
        }

        try {
            UserDTO user = new UserDTO();
            user.setId(Long.valueOf(userId));
            UserHolder.saveUser(user);
            stringRedisTemplate.expire(RedisConstants.LOGIN_TOKEN_KEY + token, RedisConstants.LOGIN_TOKEN_TTL);
        } catch (NumberFormatException exception) {
            stringRedisTemplate.delete(RedisConstants.LOGIN_TOKEN_KEY + token);
            request.setAttribute(TOKEN_INVALID_ATTRIBUTE, Boolean.TRUE);
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response,
                                Object handler,
                                Exception exception) {
        UserHolder.removeUser();
    }

    public static String resolveBearerToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (!StringUtils.hasText(authorization) || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = authorization.substring(7).trim();
        return StringUtils.hasText(token) ? token : null;
    }
}
