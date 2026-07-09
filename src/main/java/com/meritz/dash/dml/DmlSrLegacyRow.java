package com.meritz.dash.dml;

/**
 * 기간계 DML성 SR 조회 결과 1행(TBCPPE091M00 기반, 이름/유형/부서 보강).
 * {@code 쿼리/DML월별조회쿼리.sql} 을 충실히 반영하되 부서필터(:PRED_DPCD)는 제거하고
 * IT담당자 사번(PIC_EMPNO=PRCH_EMPNO)을 추가한다(HR 매칭 키).
 */
public record DmlSrLegacyRow(
        String srNo,
        String srTpcd,
        String srTpcdName,
        String bswrDetlName,
        String statusCode,
        String titlCntt,
        String msgCntt,
        String custInfoYn,
        String rqsrEmpno,
        String rqsrNm,
        String rqsrDpcd,
        String trthRqstNm,
        String trthRqstDpcd,
        String picEmpno,
        String picNm,
        String picDpcd,
        String picDpnm,
        String regDate,
        String rflcScdlDate,
        String prosCmptDate) {}
