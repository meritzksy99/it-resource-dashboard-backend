package com.meritz.dash.mapper.app;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 개발자 SR 조회의 RBAC 스코프 확정용 인사(HR_DEVELOPER) 조회 매퍼(app DB).
 * <p>
 * 기간계 SR 조회 전, "누구의 SR 을 볼 수 있는가"를 사내 인사 테이블에서 확정한다
 * (기간계와 조인하지 않는다 — 크로스 DB 금지, 장애 격리).
 */
public interface DevSrScopeMapper {

    /** 한 사번의 인사 참조(사번·이름·부서·파트). 없으면 null. */
    HrRef findRef(@Param("empno") String empno);

    /**
     * 재직(STATUS_CD='01') 직원 목록. dept/part 는 nullable 필터.
     * 반환된 empno 들이 곧 기간계 조회 대상이자 이름 보강 소스가 된다.
     */
    List<HrRef> findRefs(@Param("dept") String dept, @Param("part") String part);

    /** 사번·이름·부서·파트 참조 레코드. */
    record HrRef(String empno, String empNm, String deptCd, String partCd) {}
}
