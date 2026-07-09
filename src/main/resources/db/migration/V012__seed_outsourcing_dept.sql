-- V012: 외주 부서코드(DEPT_CD '9000' 외주) 등록. 외주직원은 이 부서로 넣으면 파트SR API에서 outsourcing으로 분리됨.
MERGE INTO CD_COMMON t USING (SELECT 'DEPT_CD' g, '9000' v, '외주' n, 9 s FROM dual) x
  ON (t.GRP_CD=x.g AND t.CD_VAL=x.v)
  WHEN NOT MATCHED THEN INSERT (GRP_CD,CD_VAL,CD_NM,SORT_NO,USE_YN) VALUES (x.g,x.v,x.n,x.s,'Y');
