package com.meritz.dash.mapper.app;

import com.meritz.dash.dml.DmlSr;
import com.meritz.dash.dml.DmlSrItem;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * DML SR 스냅샷(DASH_DML_SR)·점검/개선(DASH_DML_CHECK) 매퍼(app DB).
 * <p>
 * 배치는 {@link #mergeSnapshot}로 스냅샷만 upsert 하고 점검/개선 테이블은 건드리지 않는다(멱등·보존).
 * 점검/개선 입력은 별도 MERGE({@link #upsertCheck}, {@link #upsertImprovement})로 저장한다.
 */
public interface DmlSrMapper {

    /** DASH_DML_SR MERGE upsert (ON SR_NO). 배치 컬럼만 갱신, 점검/개선은 보존. */
    int mergeSnapshot(DmlSr sr);

    /**
     * 스냅샷 + 점검/개선 LEFT JOIN 목록. deptCd/partCd/empno/checked/improved 는 nullable 필터.
     * checked 는 NVL(CHECK_YN,'N'), improved 는 NVL(IMPROVE_YN,'N') 기준 'Y'/'N'.
     */
    List<DmlSrItem> selectList(@Param("baseYm") String baseYm, @Param("deptCd") String deptCd,
        @Param("partCd") String partCd, @Param("empno") String empno,
        @Param("checked") String checked, @Param("improved") String improved);

    /** 대상 SR 의 dev dept/part/담당자 (쓰기 RBAC 판정용). 없으면 null. */
    ScopeRef findScopeRef(@Param("srNo") String srNo);

    /** 점검 여부 upsert (DASH_DML_CHECK MERGE ON SR_NO). */
    int upsertCheck(@Param("srNo") String srNo, @Param("checkYn") String checkYn, @Param("actor") String actor);

    /** 개선대상여부(IMPROVE_YN) 토글 upsert — 점검 화면에서 개선건으로 올림/내림. */
    int upsertImproveTarget(@Param("srNo") String srNo, @Param("improveYn") String improveYn, @Param("actor") String actor);

    /** 개선건 등록/갱신 upsert — IMPROVE_YN='Y' 고정, 계획/완료예정일/완료여부/비고 저장. */
    int upsertImprovement(@Param("srNo") String srNo, @Param("improvePlan") String improvePlan,
        @Param("planCmptDate") String planCmptDate, @Param("cmptYn") String cmptYn,
        @Param("remark") String remark, @Param("actor") String actor);

    /** 대상 SR 의 스코프 참조(개발부서/파트/담당자 사번). */
    record ScopeRef(String srNo, String devDeptCd, String devPartCd, String picEmpno) {}
}
