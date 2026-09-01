package com.movielife.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.movielife.common.Result;
import com.movielife.exception.ErrorCode;
import com.movielife.utils.UserHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.method.HandlerMethod;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@Component
public class LoginInterceptor implements HandlerInterceptor {

    private final ObjectMapper objectMapper;

    public LoginInterceptor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if (!(handler instanceof HandlerMethod)
                || ((HandlerMethod) handler).getMethodAnnotation(LoginRequired.class) == null) {
            return true;
        }
        if (UserHolder.getUserId() != null) {
            return true;
        }
        ErrorCode errorCode = Boolean.TRUE.equals(
                request.getAttribute(RefreshTokenInterceptor.TOKEN_INVALID_ATTRIBUTE)
        ) ? ErrorCode.TOKEN_INVALID : ErrorCode.UNAUTHORIZED;
        response.setStatus(errorCode.getHttpStatus().value());
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        objectMapper.writeValue(
                response.getWriter(),
                Result.fail(errorCode.getCode(), errorCode.getMessage())
        );
        return false;
    }
}
