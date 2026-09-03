package com.movielife.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum ErrorCode {

    INVALID_PARAM("INVALID_PARAM", "请求参数错误", HttpStatus.BAD_REQUEST),
    INVALID_PHONE("INVALID_PHONE", "手机号格式错误", HttpStatus.BAD_REQUEST),
    CODE_REQUIRED("CODE_REQUIRED", "验证码不能为空", HttpStatus.BAD_REQUEST),
    CODE_INVALID("CODE_INVALID", "验证码错误", HttpStatus.BAD_REQUEST),
    CODE_EXPIRED("CODE_EXPIRED", "验证码已过期", HttpStatus.BAD_REQUEST),
    CODE_SEND_TOO_FREQUENT("CODE_SEND_TOO_FREQUENT", "验证码发送过于频繁", HttpStatus.TOO_MANY_REQUESTS),
    CODE_SEND_FAILED("CODE_SEND_FAILED", "验证码发送失败", HttpStatus.INTERNAL_SERVER_ERROR),
    LOGIN_FAILED("LOGIN_FAILED", "登录失败", HttpStatus.INTERNAL_SERVER_ERROR),

    UNAUTHORIZED("UNAUTHORIZED", "请先登录", HttpStatus.UNAUTHORIZED),
    TOKEN_INVALID("TOKEN_INVALID", "登录状态已失效", HttpStatus.UNAUTHORIZED),
    USER_NOT_FOUND("USER_NOT_FOUND", "用户不存在", HttpStatus.NOT_FOUND),

    INVALID_ID("INVALID_ID", "资源ID必须为正整数", HttpStatus.BAD_REQUEST),
    INVALID_PAGE("INVALID_PAGE", "页码必须大于等于1", HttpStatus.BAD_REQUEST),
    PAGE_SIZE_TOO_LARGE("PAGE_SIZE_TOO_LARGE", "每页数量必须在1到100之间", HttpStatus.BAD_REQUEST),
    INVALID_MOVIE_STATUS("INVALID_MOVIE_STATUS", "电影状态不合法", HttpStatus.BAD_REQUEST),
    INVALID_VENUE_TYPE("INVALID_VENUE_TYPE", "场馆类型不合法", HttpStatus.BAD_REQUEST),
    INVALID_BIZ_TYPE("INVALID_BIZ_TYPE", "业务类型不合法", HttpStatus.BAD_REQUEST),
    MOVIE_NOT_FOUND("MOVIE_NOT_FOUND", "电影不存在", HttpStatus.NOT_FOUND),
    VENUE_NOT_FOUND("VENUE_NOT_FOUND", "场馆不存在", HttpStatus.NOT_FOUND),
    SHOWTIME_NOT_FOUND("SHOWTIME_NOT_FOUND", "场次不存在", HttpStatus.NOT_FOUND),
    INVALID_TARGET_TYPE("INVALID_TARGET_TYPE", "目标类型不合法", HttpStatus.BAD_REQUEST),
    CONTENT_REQUIRED("CONTENT_REQUIRED", "评价内容不能为空且不能超过2000字", HttpStatus.BAD_REQUEST),
    REVIEW_NOT_FOUND("REVIEW_NOT_FOUND", "评价不存在", HttpStatus.NOT_FOUND),
    REVIEW_FORBIDDEN("REVIEW_FORBIDDEN", "无权操作该评价", HttpStatus.FORBIDDEN),
    LIKE_TARGET_INVALID("LIKE_TARGET_INVALID", "点赞目标不可用", HttpStatus.BAD_REQUEST),
    DUPLICATE_REVIEW("DUPLICATE_REVIEW", "已评价过该内容", HttpStatus.CONFLICT),
    INVALID_SCORE("INVALID_SCORE", "评分范围错误", HttpStatus.BAD_REQUEST),
    INVALID_SCORE_STEP("INVALID_SCORE_STEP", "评分必须以0.5为步长", HttpStatus.BAD_REQUEST),

    INVALID_COLLECTION_TARGET_TYPE("INVALID_COLLECTION_TARGET_TYPE", "不支持的收藏目标类型", HttpStatus.BAD_REQUEST),
    COLLECTION_TARGET_NOT_FOUND("COLLECTION_TARGET_NOT_FOUND", "收藏目标不存在", HttpStatus.NOT_FOUND),
    CANNOT_FOLLOW_SELF("CANNOT_FOLLOW_SELF", "不能关注自己", HttpStatus.BAD_REQUEST),
    POST_NOT_FOUND("POST_NOT_FOUND", "帖子不存在", HttpStatus.NOT_FOUND),
    POST_FORBIDDEN("POST_FORBIDDEN", "无权操作该帖子", HttpStatus.FORBIDDEN),
    INVALID_RELATED_TARGET("INVALID_RELATED_TARGET", "帖子关联目标不合法", HttpStatus.BAD_REQUEST),

    INTERNAL_ERROR("INTERNAL_ERROR", "服务器内部错误", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
