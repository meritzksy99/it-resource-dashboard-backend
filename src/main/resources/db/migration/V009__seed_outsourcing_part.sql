-- V009: 외주 파트 코드 (멱등 MERGE)
MERGE INTO CD_COMMON t USING (SELECT 'PART_CD' g, 'P11' v, '외주' n, 11 s FROM dual) x
  ON (t.GRP_CD=x.g AND t.CD_VAL=x.v)
  WHEN NOT MATCHED THEN INSERT (GRP_CD,CD_VAL,CD_NM,SORT_NO,USE_YN) VALUES (x.g,x.v,x.n,x.s,'Y');
