-- V011: SR_CLS 분류 2종 추가(데이터변경/원장변경) + SR_TPCD→SR_CLS(ATTR1) 재매핑
--   · 유지보수(SR_TPCD '02')를 개발요청(SR_CLS '01')로 분류
--   · 데이타변경(SR_TPCD '18') → 신규 SR_CLS '04' 데이터변경
--   · 원장변경(SR_TPCD '19')  → 신규 SR_CLS '05' 원장변경
-- 주의: 이미 집계된 DASH_DEV_AGG 는 옛 매핑으로 저장돼 있으므로, 반영하려면 해당 기간 재집계 필요.

-- 1) SR_CLS 코드 추가 (멱등 MERGE)
MERGE INTO CD_COMMON t USING (SELECT 'SR_CLS' g, '04' v, '데이터변경' n, 4 s FROM dual) x
  ON (t.GRP_CD=x.g AND t.CD_VAL=x.v)
  WHEN NOT MATCHED THEN INSERT (GRP_CD,CD_VAL,CD_NM,SORT_NO,USE_YN) VALUES (x.g,x.v,x.n,x.s,'Y');
MERGE INTO CD_COMMON t USING (SELECT 'SR_CLS' g, '05' v, '원장변경' n, 5 s FROM dual) x
  ON (t.GRP_CD=x.g AND t.CD_VAL=x.v)
  WHEN NOT MATCHED THEN INSERT (GRP_CD,CD_VAL,CD_NM,SORT_NO,USE_YN) VALUES (x.g,x.v,x.n,x.s,'Y');

-- 2) SR_TPCD → SR_CLS(ATTR1) 재매핑
UPDATE CD_COMMON SET ATTR1='01' WHERE GRP_CD='SR_TPCD' AND CD_VAL='02';  -- 유지보수 → 개발요청
UPDATE CD_COMMON SET ATTR1='04' WHERE GRP_CD='SR_TPCD' AND CD_VAL='18';  -- 데이타변경 → 데이터변경
UPDATE CD_COMMON SET ATTR1='05' WHERE GRP_CD='SR_TPCD' AND CD_VAL='19';  -- 원장변경 → 원장변경
