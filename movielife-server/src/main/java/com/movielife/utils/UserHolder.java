package com.movielife.utils;

import com.movielife.dto.UserDTO;

public final class UserHolder {

    private static final ThreadLocal<UserDTO> USER_THREAD_LOCAL = new ThreadLocal<>();

    public static void saveUser(UserDTO user) {
        USER_THREAD_LOCAL.set(user);
    }

    public static UserDTO getUser() {
        return USER_THREAD_LOCAL.get();
    }

    public static Long getUserId() {
        UserDTO user = getUser();
        return user == null ? null : user.getId();
    }

    public static void removeUser() {
        USER_THREAD_LOCAL.remove();
    }

    private UserHolder() {
    }
}
