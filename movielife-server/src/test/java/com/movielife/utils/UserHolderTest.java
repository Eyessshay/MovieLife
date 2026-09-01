package com.movielife.utils;

import com.movielife.dto.UserDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserHolderTest {

    @AfterEach
    void cleanup() {
        UserHolder.removeUser();
    }

    @Test
    void shouldSaveReadAndRemoveCurrentUser() {
        UserDTO user = new UserDTO();
        user.setId(7L);

        UserHolder.saveUser(user);
        assertThat(UserHolder.getUserId()).isEqualTo(7L);

        UserHolder.removeUser();
        assertThat(UserHolder.getUser()).isNull();
    }
}
