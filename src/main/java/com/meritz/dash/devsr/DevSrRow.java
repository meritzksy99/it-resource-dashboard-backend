package com.meritz.dash.devsr;

/**
 * 기간계(TBCPPE091M00/093L00) 실시간 SR 조회 결과 한 행.
 * <p>
 * 두 갈래로 소유자(empno)가 정해진다:
 * <ul>
 *   <li>계획 수립 SR(승인 작업이력 존재) → 담당자(093.SPIC_EMPNO) 기준, 본인 작업시간/계획 M/M 합</li>
 *   <li>SR등록('02', 계획 미수립) → 신청자(091.PRCH_EMPNO) 기준, 시간/MM 없음</li>
 * </ul>
 *
 * @param empno        소유 사번 (담당자 또는 신청자)
 * @param srNo         SR 번호
 * @param statusCode   SR 상태코드 (SR_REG_STAT_CODE) — 한글명은 CD_COMMON 에서 보강
 * @param srTpcd       SR 유형코드 (SR_TPCD) — 한글명은 CD_COMMON 에서 보강
 * @param rflcScdlDate 반영예정일자 (YYYYMMDD, 미수립이면 null)
 * @param titlCntt     SR 제목 (TITL_CNTT)
 * @param msgCntt      SR 내용 (MSG_CNTT)
 * @param jobHours     담당자의 승인 작업시간 합(JOB_EXEC_HOUR) — 계획 미수립이면 0
 * @param jobMm        담당자의 승인 계획 M/M 합(JOB_MANM) — 계획 미수립이면 0
 * @param planYn       계획 수립 여부 'Y'/'N'
 */
public record DevSrRow(
        String empno,
        String srNo,
        String statusCode,
        String srTpcd,
        String rflcScdlDate,
        String titlCntt,
        String msgCntt,
        double jobHours,
        double jobMm,
        String planYn) {}
