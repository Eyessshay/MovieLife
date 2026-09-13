package com.movielife.exception;

import com.movielife.common.Result;
import com.movielife.enums.VenueType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import javax.validation.ConstraintViolationException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException exception) {
        ErrorCode errorCode = exception.getErrorCode();
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(Result.fail(errorCode.getCode(), errorCode.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException exception) {
        FieldError fieldError = exception.getBindingResult().getFieldError();
        return validationFailure(fieldError);
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result<Void>> handleBindException(BindException exception) {
        return validationFailure(exception.getBindingResult().getFieldError());
    }

    @ExceptionHandler({
            ConstraintViolationException.class,
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class
    })
    public ResponseEntity<Result<Void>> handleInvalidRequest(Exception exception) {
        return ResponseEntity.badRequest()
                .body(Result.fail(ErrorCode.INVALID_PARAM.getCode(), ErrorCode.INVALID_PARAM.getMessage()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        ErrorCode errorCode;
        if ("status".equals(exception.getName())) {
            errorCode = ErrorCode.INVALID_MOVIE_STATUS;
        } else if (VenueType.class.equals(exception.getRequiredType())) {
            errorCode = ErrorCode.INVALID_VENUE_TYPE;
        } else if ("id".equals(exception.getName())) {
            errorCode = ErrorCode.INVALID_ID;
        } else {
            errorCode = ErrorCode.INVALID_PARAM;
        }
        return ResponseEntity.badRequest()
                .body(Result.fail(errorCode.getCode(), errorCode.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnknownException(Exception exception) {
        log.error("Unhandled exception", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail(ErrorCode.INTERNAL_ERROR.getCode(), ErrorCode.INTERNAL_ERROR.getMessage()));
    }

    private ResponseEntity<Result<Void>> validationFailure(FieldError fieldError) {
        ErrorCode errorCode = validationErrorCode(fieldError);
        String message = fieldError == null || isTypeMismatch(fieldError)
                ? errorCode.getMessage()
                : fieldError.getDefaultMessage();
        return ResponseEntity.badRequest().body(Result.fail(errorCode.getCode(), message));
    }

    private boolean isTypeMismatch(FieldError fieldError) {
        return fieldError.getCode() != null && fieldError.getCode().startsWith("typeMismatch");
    }

    private ErrorCode validationErrorCode(FieldError fieldError) {
        if (fieldError == null) {
            return ErrorCode.INVALID_PARAM;
        }
        if ("phone".equals(fieldError.getField())) {
            return ErrorCode.INVALID_PHONE;
        }
        if ("code".equals(fieldError.getField()) && "NotBlank".equals(fieldError.getCode())) {
            return ErrorCode.CODE_REQUIRED;
        }
        if ("code".equals(fieldError.getField())) {
            return ErrorCode.CODE_INVALID;
        }
        if ("page".equals(fieldError.getField())) {
            return ErrorCode.INVALID_PAGE;
        }
        if ("pageSize".equals(fieldError.getField())) {
            return ErrorCode.PAGE_SIZE_TOO_LARGE;
        }
        if ("status".equals(fieldError.getField())) {
            return ErrorCode.INVALID_MOVIE_STATUS;
        }
        if ("type".equals(fieldError.getField())
                || "venueType".equals(fieldError.getField())) {
            return ErrorCode.INVALID_VENUE_TYPE;
        }
        if ("bizTypeValid".equals(fieldError.getField())) {
            return ErrorCode.INVALID_BIZ_TYPE;
        }
        if ("targetType".equals(fieldError.getField())) {
            return ErrorCode.INVALID_TARGET_TYPE;
        }
        if ("targetId".equals(fieldError.getField()) || "id".equals(fieldError.getField())) {
            return ErrorCode.INVALID_ID;
        }
        if ("score".equals(fieldError.getField())) {
            return ErrorCode.INVALID_SCORE;
        }
        if ("content".equals(fieldError.getField())) {
            return ErrorCode.CONTENT_REQUIRED;
        }
        return ErrorCode.INVALID_PARAM;
    }
}
