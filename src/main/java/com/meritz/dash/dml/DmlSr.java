package com.meritz.dash.dml;

/**
 * DASH_DML_SR 스냅샷 저장 단위. 기간계 조회 행({@link DmlSrLegacyRow})을 HR_DEVELOPER 와 매칭해
 * baseYm(등록월)·devDeptCd·devPartCd 를 확정한 뒤 MERGE upsert 한다.
 */
public record DmlSr(
        String srNo,
        String baseYm,
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
        String devDeptCd,
        String devPartCd,
        String regDate,
        String rflcScdlDate,
        String prosCmptDate) {}
