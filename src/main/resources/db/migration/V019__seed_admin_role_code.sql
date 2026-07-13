-- V019: AD/게이트웨이 전환 후속 — 관리자 역할 코드 등록.
-- admin 계정 지름길(app.admin) 제거로 ADMIN 권한은 HR_DEVELOPER.ROLE_CD='ADMIN' 행으로만 성립한다.
-- 매직 문자열 방지를 위해 EMP_ROLE 코드 그룹에 ADMIN을 정식 등록(운영 ADMIN 지정 시 이 코드값 사용).
INSERT INTO CD_COMMON (GRP_CD, CD_VAL, CD_NM, SORT_NO) VALUES ('EMP_ROLE','ADMIN','관리자',4);
