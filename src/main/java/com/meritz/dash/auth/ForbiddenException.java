package com.meritz.dash.auth;

public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String msg) { super(msg); }
}
