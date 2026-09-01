package com.movielife.vo;

import com.movielife.dto.UserDTO;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginVO {

    private String token;
    private long expiresIn;
    private UserDTO user;
}
