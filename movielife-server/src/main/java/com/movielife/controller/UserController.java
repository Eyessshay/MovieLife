package com.movielife.controller;

import com.movielife.common.Result;
import com.movielife.dto.LoginDTO;
import com.movielife.dto.SendCodeDTO;
import com.movielife.interceptor.RefreshTokenInterceptor;
import com.movielife.interceptor.LoginRequired;
import com.movielife.service.UserService;
import com.movielife.vo.LoginVO;
import com.movielife.vo.UserMeVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping("/code")
    public Result<Void> sendCode(@Valid @RequestBody SendCodeDTO request) {
        userService.sendCode(request);
        return Result.ok();
    }

    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO request) {
        return Result.ok(userService.login(request));
    }

    @GetMapping("/me")
    @LoginRequired
    public Result<UserMeVO> me() {
        return Result.ok(userService.getCurrentUser());
    }

    @PostMapping("/logout")
    @LoginRequired
    public Result<Void> logout(HttpServletRequest request) {
        userService.logout(RefreshTokenInterceptor.resolveBearerToken(request));
        return Result.ok();
    }
}
