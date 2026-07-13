package com.meritz.dash.common;

/**
 * 리소스 상태 충돌(409 Conflict). {@link GlobalExceptionHandler} 가 ProblemDetail(409) 로 변환한다.
 * 예) (week, srNo, 작성자) 중복 주간보고 등록, 유니크 제약 위반 레이스(DuplicateKeyException) 변환.
 */
public class ConflictException extends RuntimeException {
    public ConflictException(String msg) {
        super(msg);
    }
}
