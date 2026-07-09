package com.meritz.dash.mapper.legacy;

import com.meritz.dash.devsr.DevSrRow;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 기간계(조회 전용) 개발자별 실시간 SR 현황 매퍼.
 * <p>
 * <b>SELECT 전용</b> — INSERT/UPDATE/DELETE/MERGE/DDL 절대 금지(CLAUDE.md 3.2).
 * 값 주입은 바인드 변수(#{})만 사용한다.
 */
public interface DevSrMapper {

    /**
     * 담당 개발자 사번 목록에 해당하는 현재 진행중(종료 제외) SR 목록을 반환한다.
     * <ul>
     *   <li>승인 작업이력(093, APRV_YN='Y')이 있으면 해당 개발자의 작업시간·계획 M/M 합산(planYn='Y')</li>
     *   <li>담당(091.SPIC_EMPNO)이나 승인 작업이력이 없으면 계획 미수립(planYn='N', 시간/MM 0)</li>
     * </ul>
     * empnos 가 비면 호출하지 않는다(서비스에서 방어) — IN () 방지.
     */
    List<DevSrRow> selectDevSrs(@Param("empnos") List<String> empnos);
}
