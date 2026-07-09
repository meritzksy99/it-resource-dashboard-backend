-- V014: 야근시간 업로드 테이블 (19c 호환)
--
-- HR_OVERTIME: 근태 시스템에서 내려받은 월별 야근 엑셀(야근양식)을 업로드해 저장한다.
--   OT_MINUTES = 평일연장(분) + 평일야간(분) + 휴일연장(분) + 휴일야간(분) — 그 달 야근 총 '분'.
--   업로드는 period 단위 멱등(해당 월 DELETE 후 INSERT — 재업로드 안전).
--   야근 조회 API(/api/v1/resource/overtime)는 이 테이블에서 읽는다(분→시간 환산은 서비스).

CREATE TABLE HR_OVERTIME (
  PERIOD_YM  VARCHAR2(6)  NOT NULL,
  EMPNO      VARCHAR2(20) NOT NULL,
  OT_MINUTES NUMBER(7)    DEFAULT 0 NOT NULL,
  CREATED_AT TIMESTAMP    DEFAULT SYSTIMESTAMP NOT NULL,
  CREATED_BY VARCHAR2(30) DEFAULT 'SYSTEM' NOT NULL,
  UPDATED_AT TIMESTAMP,
  UPDATED_BY VARCHAR2(30),
  CONSTRAINT PK_HR_OVERTIME PRIMARY KEY (PERIOD_YM, EMPNO),
  CONSTRAINT CK_HR_OVERTIME_MIN CHECK (OT_MINUTES >= 0)
);
