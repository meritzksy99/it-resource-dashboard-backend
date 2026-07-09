package com.meritz.dash.mapper.legacy;

import com.meritz.dash.dml.DmlSrLegacyRow;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 기간계(조회 전용) DML성 SR 월별 조회 매퍼.
 * <p>
 * <b>SELECT 전용</b> — INSERT/UPDATE/DELETE/MERGE/DDL 절대 금지(CLAUDE.md 3.2).
 * 값 주입은 바인드 변수(#{})만 사용한다.
 */
public interface DmlSrLegacyMapper {

    /**
     * 기준월(yyyyMM)에 등록된 DML성 SR(유형 18=데이타변경, 19=원장변경) 목록을 반환한다.
     * <ul>
     *   <li>승인 작업이력(093) 존재 SR 만 대상(INNER JOIN) — DISTINCT 로 fan-out 중복 제거</li>
     *   <li>신청자/실제요청자/IT담당자 이름·부서는 인사(TBCPPU001I00)·부서(TBCPPD001M00)에서 보강</li>
     * </ul>
     *
     * @param baseYm 기준월(yyyyMM) — {@code SUBSTR(REG_DATE,1,6)} 과 비교
     */
    List<DmlSrLegacyRow> selectDmlSrs(@Param("baseYm") String baseYm);
}
