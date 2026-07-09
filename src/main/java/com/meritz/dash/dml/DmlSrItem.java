package com.meritz.dash.dml;

/**
 * DML SR 목록 API 응답 항목: 배치 스냅샷(DASH_DML_SR) + 점검/개선(DASH_DML_CHECK) LEFT JOIN 결과.
 * 점검/개선 행이 없으면 checkYn/improveYn/cmptYn='N', 나머지 null.
 */
public record DmlSrItem(
        String srNo,
        String baseYm,
        String srTpcd,
        String srTpcdName,
        String bswrDetlName,
        String statusCode,
        String titlCntt,
        String msgCntt,
        String custInfoYn,
        String rqsrNm,
        String rqsrDpcd,
        String trthRqstNm,
        String trthRqstDpcd,
        String picEmpno,
        String picNm,
        String picDpnm,
        String devDeptCd,
        String devPartCd,
        String regDate,
        String rflcScdlDate,
        String prosCmptDate,
        String checkYn,
        String improveYn,
        String improvePlan,
        String planCmptDate,
        String cmptYn,
        String remark) {}
