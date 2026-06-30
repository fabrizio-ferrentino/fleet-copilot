package com.fleetcopilot.api;

import com.fleetcopilot.domain.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Maps domain and validation errors to compact JSON error responses. */
@RestControllerAdvice
class ApiExceptionHandler {

  record ApiError(String error) {}

  @ExceptionHandler(NotFoundException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  ApiError notFound(NotFoundException e) {
    return new ApiError(e.getMessage());
  }

  @ExceptionHandler(IllegalArgumentException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  ApiError badRequest(IllegalArgumentException e) {
    return new ApiError(e.getMessage());
  }

  @ExceptionHandler(IllegalStateException.class)
  @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
  ApiError unavailable(IllegalStateException e) {
    return new ApiError(e.getMessage());
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  ApiError typeMismatch(MethodArgumentTypeMismatchException e) {
    return new ApiError("invalid value for parameter '" + e.getName() + "'");
  }
}
