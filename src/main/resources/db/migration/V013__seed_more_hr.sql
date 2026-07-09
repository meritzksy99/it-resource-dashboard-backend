-- V013: 인력 시드 추가 — AI솔루션팀(DEPT_CD '2735') 8명 + IT개발팀(DEPT_CD '2139') 6명
--
-- 부서코드: 운영/실 appuser DB 및 CD_COMMON(GRP_CD='DEPT_CD', V008)·API 문서 관례를 따른다 —
--   '2139'=IT개발팀, '2735'=AI솔루션팀. (참고: Flyway 테스트 시드 V002 의 'D101' 은 테스트 전용 레거시 값으로,
--   기존 인력 E0001~E0004 의 부서코드는 이 파일에서 건드리지 않는다 — 정규화 UPDATE 없음.)
-- 파트코드: 기존 P01~P11(V008/V009) 사용, AI솔루션팀용 파트 P12~P14 신규 추가(멱등 MERGE).
-- STATUS_CD 는 V003에서 코드화: '01' 재직 / '02' 휴직. ROLE_CD: '01' 팀장 / '02' 업무리더 / '03' 일반직원.
-- 사번은 4자리 숫자(운영 DB 관례) 6001~6008(AI) / 6101~6106(개발) — 기존 사번과 충돌 없음.

-- ─────────────────────────────────────────────────────────────
-- 1) AI솔루션팀용 파트 코드 (멱등 MERGE — V008/V009 스타일)
-- ─────────────────────────────────────────────────────────────
MERGE INTO CD_COMMON t USING (SELECT 'PART_CD' g, 'P12' v, 'AI플랫폼' n, 12 s FROM dual) x
  ON (t.GRP_CD=x.g AND t.CD_VAL=x.v)
  WHEN NOT MATCHED THEN INSERT (GRP_CD,CD_VAL,CD_NM,SORT_NO,USE_YN) VALUES (x.g,x.v,x.n,x.s,'Y');
MERGE INTO CD_COMMON t USING (SELECT 'PART_CD' g, 'P13' v, 'AI서비스' n, 13 s FROM dual) x
  ON (t.GRP_CD=x.g AND t.CD_VAL=x.v)
  WHEN NOT MATCHED THEN INSERT (GRP_CD,CD_VAL,CD_NM,SORT_NO,USE_YN) VALUES (x.g,x.v,x.n,x.s,'Y');
MERGE INTO CD_COMMON t USING (SELECT 'PART_CD' g, 'P14' v, '데이터분석' n, 14 s FROM dual) x
  ON (t.GRP_CD=x.g AND t.CD_VAL=x.v)
  WHEN NOT MATCHED THEN INSERT (GRP_CD,CD_VAL,CD_NM,SORT_NO,USE_YN) VALUES (x.g,x.v,x.n,x.s,'Y');

-- ─────────────────────────────────────────────────────────────
-- 2) AI솔루션팀('2735') — 팀장 1 + 업무리더 2 + 일반직원 5 = 8명 (6001~6008)
-- ─────────────────────────────────────────────────────────────
INSERT INTO HR_DEVELOPER (EMPNO, EMP_NM, DEPT_CD, PART_CD, GRADE_CD, ROLE_CD, DEV_YN, STATUS_CD)
  VALUES ('6001','정하늘','2735','P12','부장','01','N','01');
INSERT INTO HR_DEVELOPER (EMPNO, EMP_NM, DEPT_CD, PART_CD, GRADE_CD, ROLE_CD, DEV_YN, STATUS_CD)
  VALUES ('6002','강도윤','2735','P12','차장','02','Y','01');
INSERT INTO HR_DEVELOPER (EMPNO, EMP_NM, DEPT_CD, PART_CD, GRADE_CD, ROLE_CD, DEV_YN, STATUS_CD)
  VALUES ('6003','윤서아','2735','P13','차장','02','Y','01');
INSERT INTO HR_DEVELOPER (EMPNO, EMP_NM, DEPT_CD, PART_CD, GRADE_CD, ROLE_CD, DEV_YN, STATUS_CD)
  VALUES ('6004','임준호','2735','P12','과장','03','Y','01');
INSERT INTO HR_DEVELOPER (EMPNO, EMP_NM, DEPT_CD, PART_CD, GRADE_CD, ROLE_CD, DEV_YN, STATUS_CD)
  VALUES ('6005','한지민','2735','P13','과장','03','Y','01');
INSERT INTO HR_DEVELOPER (EMPNO, EMP_NM, DEPT_CD, PART_CD, GRADE_CD, ROLE_CD, DEV_YN, STATUS_CD)
  VALUES ('6006','오시우','2735','P13','대리','03','Y','01');
INSERT INTO HR_DEVELOPER (EMPNO, EMP_NM, DEPT_CD, PART_CD, GRADE_CD, ROLE_CD, DEV_YN, STATUS_CD)
  VALUES ('6007','서예린','2735','P14','대리','03','Y','01');
INSERT INTO HR_DEVELOPER (EMPNO, EMP_NM, DEPT_CD, PART_CD, GRADE_CD, ROLE_CD, DEV_YN, STATUS_CD)
  VALUES ('6008','문가온','2735','P14','사원','03','Y','01');

-- ─────────────────────────────────────────────────────────────
-- 3) IT개발팀('2139') 추가 — 업무리더 1 + 일반직원 5 = 6명 (6101~6106)
--    기존 파트(P01 금융상품 / P02 계좌 / P03 MTS / P04 HTS / P05 출납) 재사용, 파트 분산.
-- ─────────────────────────────────────────────────────────────
INSERT INTO HR_DEVELOPER (EMPNO, EMP_NM, DEPT_CD, PART_CD, GRADE_CD, ROLE_CD, DEV_YN, STATUS_CD)
  VALUES ('6101','배수현','2139','P03','차장','02','Y','01');
INSERT INTO HR_DEVELOPER (EMPNO, EMP_NM, DEPT_CD, PART_CD, GRADE_CD, ROLE_CD, DEV_YN, STATUS_CD)
  VALUES ('6102','신재원','2139','P01','과장','03','Y','01');
INSERT INTO HR_DEVELOPER (EMPNO, EMP_NM, DEPT_CD, PART_CD, GRADE_CD, ROLE_CD, DEV_YN, STATUS_CD)
  VALUES ('6103','조민서','2139','P02','대리','03','Y','01');
INSERT INTO HR_DEVELOPER (EMPNO, EMP_NM, DEPT_CD, PART_CD, GRADE_CD, ROLE_CD, DEV_YN, STATUS_CD)
  VALUES ('6104','황인우','2139','P03','대리','03','Y','01');
INSERT INTO HR_DEVELOPER (EMPNO, EMP_NM, DEPT_CD, PART_CD, GRADE_CD, ROLE_CD, DEV_YN, STATUS_CD)
  VALUES ('6105','안소율','2139','P04','사원','03','Y','01');
INSERT INTO HR_DEVELOPER (EMPNO, EMP_NM, DEPT_CD, PART_CD, GRADE_CD, ROLE_CD, DEV_YN, STATUS_CD)
  VALUES ('6106','류건우','2139','P05','사원','03','Y','01');
