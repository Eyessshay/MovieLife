package com.movielife.constants;

import java.time.Duration;

public final class RedisConstants {

    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Duration LOGIN_CODE_TTL = Duration.ofMinutes(5);

    public static final String LOGIN_CODE_LIMIT_KEY = "login:code:limit:";
    public static final Duration LOGIN_CODE_LIMIT_TTL = Duration.ofSeconds(60);

    public static final String LOGIN_TOKEN_KEY = "login:token:";
    public static final Duration LOGIN_TOKEN_TTL = Duration.ofMinutes(60);

    public static final String MOVIE_DETAIL_KEY = "movie:detail:";
    public static final Duration MOVIE_DETAIL_TTL = Duration.ofMinutes(30);
    public static final long MOVIE_DETAIL_TTL_RANDOM_MINUTES = 10L;
    public static final Duration MOVIE_DETAIL_NULL_TTL = Duration.ofMinutes(2);

    public static final String VENUE_DETAIL_KEY = "venue:detail:";
    public static final Duration VENUE_DETAIL_TTL = Duration.ofMinutes(30);
    public static final long VENUE_DETAIL_TTL_RANDOM_MINUTES = 10L;
    public static final Duration VENUE_DETAIL_NULL_TTL = Duration.ofMinutes(2);
    public static final String VENUE_GEO_KEY = "venue:geo:";

    public static final String REVIEW_LIKE_KEY = "review:like:";
    public static final String POST_LIKE_KEY = "post:like:";
    public static final String FEED_KEY = "feed:";

    private RedisConstants() {
    }
}
