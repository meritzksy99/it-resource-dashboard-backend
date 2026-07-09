package com.meritz.dash.common;

/** 대상 리소스가 없을 때(404). GlobalExceptionHandler 가 ProblemDetail(NOT_FOUND)로 변환한다. */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String msg) { super(msg); }
}
