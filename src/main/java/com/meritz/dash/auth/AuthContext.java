package com.meritz.dash.auth;

/**
 * ThreadLocal 기반 인증 컨텍스트.
 * Task6 인터셉터가 요청 시작 시 set(), 종료 시 clear()를 호출한다.
 */
public final class AuthContext {

    private static final ThreadLocal<String> EMPNO   = new ThreadLocal<>();
    private static final ThreadLocal<String> ROLE    = new ThreadLocal<>();
    private static final ThreadLocal<String> DEPT_CD = new ThreadLocal<>();
    private static final ThreadLocal<String> PART_CD = new ThreadLocal<>();

    private AuthContext() {}

    public static void set(String empno, String role, String deptCd, String partCd) {
        EMPNO.set(empno);
        ROLE.set(role);
        DEPT_CD.set(deptCd);
        PART_CD.set(partCd);
    }

    public static void clear() {
        EMPNO.remove();
        ROLE.remove();
        DEPT_CD.remove();
        PART_CD.remove();
    }

    /** 현재 요청의 사번을 반환한다. 인증 컨텍스트가 없으면 UnauthorizedException. */
    public static String empno() {
        String v = EMPNO.get();
        if (v == null) throw new UnauthorizedException("인증이 필요합니다");
        return v;
    }

    /** 현재 요청의 역할 코드를 반환한다. 인증 컨텍스트가 없으면 null. */
    public static String role() {
        return ROLE.get();
    }

    /** 현재 요청의 부서코드를 반환한다. admin 또는 구 토큰이면 null. */
    public static String deptCd() {
        return DEPT_CD.get();
    }

    /** 현재 요청의 파트코드를 반환한다. admin 또는 미설정이면 null. */
    public static String partCd() {
        return PART_CD.get();
    }
}
