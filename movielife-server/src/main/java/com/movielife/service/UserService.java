package com.movielife.service;

import com.movielife.dto.LoginDTO;
import com.movielife.dto.SendCodeDTO;
import com.movielife.vo.LoginVO;
import com.movielife.vo.UserMeVO;

public interface UserService {

    void sendCode(SendCodeDTO request);

    LoginVO login(LoginDTO request);

    UserMeVO getCurrentUser();

    void logout(String token);
}
