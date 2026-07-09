# IT 개발팀 리소스 대시보드 — API 사용 가이드

프론트엔드/외부 호출자를 위한 **API별 입력·출력 예시 + 화면 활용법** 문서입니다.
실제 서버 응답값을 기반으로 작성했습니다.

---

## 0. 공통 규약 (먼저 읽기)

- **Base URL**: `http://<서버IP>:8080/api/v1`
  - 로컬: `http://localhost:8080/api/v1`
  - 같은 WiFi의 다른 PC: `http://172.22.51.226:8080/api/v1` (서버 PC의 IP)
- **Swagger UI**(브라우저에서 직접 테스트): `http://<서버IP>:8080/swagger-ui/index.html`
- **성공 응답 형태(envelope)** — 모든 API 공통:
  ```json
  { "data": <실제 데이터>, "meta": <목록 정보 등 부가정보 or null> }
  ```
- **에러 응답 형태(RFC 7807 ProblemDetail)** — 모든 에러 공통:
  ```json
  { "type":"about:blank", "title":"Bad Request", "status":400,
    "detail":"period는 YYYYMM 형식이어야 합니다", "instance":"/api/v1/resource" }
  ```
  프론트에서는 `status`로 분기(400 검증실패 / 401 미인증 / 403 권한없음 / 404 없음)하고, `detail`을 사용자 메시지로 쓰면 됩니다.

### 인증(로그인)이 필요한가?
| 구분 | 대상 | 토큰 |
|---|---|---|
| **공개** | `/health`, `/auth/login`, Swagger | 불필요 |
| **인증 필요**(로그인만 되면 OK) | 모든 조회(GET) — codes, developers, dev-volume, resource, sr-projects, aggregations(이력), `/auth/me`, `/auth/password` | 필요 |
| **팀장 또는 ADMIN만** | 쓰기 — developers(POST/PUT/DELETE), codes(POST/PUT/DELETE), aggregations(POST 집계실행) | 필요 + 역할 |

- **역할 코드**: `01`=팀장, `02`=업무리더, `03`=일반직원, `ADMIN`=관리자 계정
- **토큰 붙이는 법**: 로그인으로 받은 `token`을 모든 요청 헤더에 `Authorization: Bearer <token>` 로 첨부.
- **Swagger에서**: 우측 상단 `Authorize` 버튼 → `Bearer <token>` 입력하면 이후 호출에 자동 첨부.

---

## 1. 인증 (Auth)

### 1-1. 로그인 — `POST /api/v1/auth/login` 🔓공개

로그인해서 JWT 토큰을 받습니다. **ID=사번, 초기 비밀번호=사번**(첫 로그인 시 변경 필요). 관리자는 `admin`/`admin`.

**요청**
```json
{ "empno": "E0001", "password": "E0001" }
```

**응답 (200)**
```json
{
  "data": {
    "token": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJFMDAwMS...",
    "empno": "E0001",
    "role": "01",
    "roleName": "팀장",
    "name": "김팀장",
    "pwdResetRequired": true
  },
  "meta": null
}
```
**실패 (401)** — 사번/비번 불일치:
```json
{ "title":"Unauthorized", "status":401, "detail":"아이디 또는 비밀번호가 올바르지 않습니다" }
```

**화면 활용**
- 로그인 폼(사번 + 비밀번호) → 이 API 호출 → 성공 시 `token`을 `localStorage`에 저장하고, 이후 모든 API 호출 인터셉터에서 `Authorization` 헤더로 자동 첨부.
- `role`/`roleName`으로 **메뉴·버튼 노출 제어**(예: `01`/`ADMIN`이 아니면 "인사 등록"·"코드 관리" 버튼 숨김).
- **`pwdResetRequired: true`면** 로그인 직후 "비밀번호 변경" 화면으로 강제 이동시키기.

---

### 1-2. 비밀번호 변경 — `POST /api/v1/auth/password` 🔒인증

첫 로그인 강제 변경 + 자발적 변경 공용. **본인 계정만**.

**요청**
```json
{ "oldPassword": "E0002", "newPassword": "newpass123" }
```
**응답 (200)**: `{ "data": null, "meta": null }`
**실패 (400)**: 현재 비번 불일치 / 새 비번 8자 미만 / 사번과 동일
```json
{ "status":400, "detail":"새 비밀번호는 8자 이상이어야 합니다" }
```

**화면 활용**
- `pwdResetRequired`가 true인 사용자에게 강제로 띄우는 비밀번호 변경 폼(현재 비번 + 새 비번 + 새 비번 확인).
- 성공 후 다시 로그인시키거나, 재로그인해 새 토큰의 `pwdResetRequired: false`를 확인.

---

### 1-3. 내 정보 — `GET /api/v1/auth/me` 🔒인증

토큰 소유자의 정보를 반환(헤더 사진·이름 표시, 권한 재확인용).

**응답 (200)**
```json
{ "data": { "empno":"E0001","role":"01","roleName":"팀장","name":"김팀장","partCd":"P01","pwdResetRequired":true }, "meta": null }
```

**화면 활용**: 새로고침/앱 진입 시 토큰으로 현재 사용자 정보 복원 → 헤더에 "김팀장(팀장)" 표시, 권한 기반 UI 렌더링.

---

## 2. 헬스 체크 — `GET /api/v1/health` 🔓공개

서버 살아있는지 확인(프론트 연결 테스트용).

**응답 (200)**: `{ "data": { "status":"UP", "timestamp":"2026-07-01T00:00:00Z" }, "meta": null }`

**화면 활용**: 앱 시작 시 서버 연결 확인, 상태 배지. 인증 불필요라 로그인 전에도 호출 가능.

---

## 3. 공통코드 (Codes)

SR유형·역할·상태 등 코드값을 관리. 드롭다운/배지 표시에 사용.

### 3-1. 코드 조회 — `GET /api/v1/codes?grpCd=SR_TPCD` 🔒인증

활성(USE_YN='Y') 코드만, SORT_NO 순.

**주요 그룹코드(grpCd)**: `SR_TPCD`(SR유형), `SR_CLS`(SR분류), `EMP_ROLE`(역할), `EMP_STATUS`(재직상태)

**응답 (200)**
```json
{
  "data": [
    { "grpCd":"SR_TPCD","cdVal":"01","cdNm":"개발요청","sortNo":1 },
    { "grpCd":"SR_TPCD","cdVal":"02","cdNm":"유지보수","sortNo":2 },
    { "grpCd":"SR_TPCD","cdVal":"03","cdNm":"자료요청","sortNo":3 }
  ],
  "meta": { "grpCd":"SR_TPCD", "count":7 }
}
```

**화면 활용**: 셀렉트박스/필터/배지 라벨. 예) SR유형 드롭다운 옵션, 코드→이름 매핑 테이블(`{"01":"개발요청",...}`)로 다른 화면에서 코드를 한글명으로 치환.

### 3-2. 코드 등록 — `POST /api/v1/codes` 🔒팀장·ADMIN

**요청**
```json
{ "grpCd":"SR_TPCD","cdVal":"20","cdNm":"신규유형","sortNo":8,"attr1":"99" }
```
**응답 (201)**: `{ "data": { "grpCd":"SR_TPCD","cdVal":"20","cdNm":"신규유형","sortNo":8 }, "meta": null }`
- 이미 있는 활성 코드면 **400**("이미 존재하는 코드"). **비활성 코드였으면 자동 재활성화**.
- 등록 시 항상 활성(USE_YN='Y')으로 저장.

### 3-3. 코드 수정 — `PUT /api/v1/codes/{grpCd}/{cdVal}` 🔒팀장·ADMIN
**요청**: `PUT /api/v1/codes/SR_TPCD/20`
```json
{ "grpCd":"SR_TPCD","cdVal":"20","cdNm":"신규유형(수정)","sortNo":9,"useYn":"Y","attr1":"99" }
```
**응답 (200)**: 수정된 코드.

### 3-4. 코드 삭제(비활성화) — `DELETE /api/v1/codes/{grpCd}/{cdVal}` 🔒팀장·ADMIN
**요청**: `DELETE /api/v1/codes/SR_TPCD/20` → **응답 (204, 본문 없음)**.
- 물리 삭제가 아니라 **USE_YN='N'** 처리. 조회(GET)에서 사라짐. 같은 코드 재등록 시 되살아남.

**화면 활용(코드 관리 화면)**: 코드 목록 테이블 + 추가/수정/삭제 버튼(팀장·ADMIN에게만 노출). 삭제는 "비활성화"로 표기.

---

## 4. 인력 (Developers) — 인사정보

### 4-1. 목록 — `GET /api/v1/developers?part=&devYn=&status=` 🔒인증
필터(선택): `part`(파트), `devYn`(Y/N 개발자여부), `status`(01재직/02휴직).

**응답 (200)**
```json
{
  "data": [
    { "empno":"E0001","empNm":"김팀장","deptCd":"D101","partCd":"P01","gradeCd":"부장","roleCd":"01","devYn":"N","statusCd":"01" },
    { "empno":"E0002","empNm":"이개발","deptCd":"D101","partCd":"P01","gradeCd":"과장","roleCd":"03","devYn":"Y","statusCd":"01" }
  ],
  "meta": { "count":4 }
}
```
**화면 활용**: 인사 목록 테이블. `roleCd`/`statusCd`는 공통코드(EMP_ROLE/EMP_STATUS)로 한글 치환.

### 4-2. 단건 — `GET /api/v1/developers/{empno}` 🔒인증
`GET /api/v1/developers/E0001` → `{ "data": { ...E0001... }, "meta": null }`. 없으면 **400**("사번 … 인력이 없습니다").

### 4-3. 등록 — `POST /api/v1/developers` 🔒팀장·ADMIN
**요청**
```json
{ "empno":"E0005","empNm":"신입","deptCd":"D101","partCd":"P02","gradeCd":"사원","roleCd":"03","devYn":"Y","statusCd":"01" }
```
**응답 (201)**: 생성된 인력. 검증 실패(사번/이름 누락 등) → **400**.
> 참고: 신규 인력은 다음 서버 기동 시 로그인 계정이 자동 생성됩니다(초기 비번=사번).

### 4-4. 수정 — `PUT /api/v1/developers/{empno}` 🔒팀장·ADMIN
`PUT /api/v1/developers/E0005` + 바디(위와 동일 형식) → **200**.

### 4-5. 삭제 — `DELETE /api/v1/developers/{empno}` 🔒팀장·ADMIN
`DELETE /api/v1/developers/E0005` → **204**.

**화면 활용(인사 관리 화면)**: 목록 테이블 + CRUD 폼(팀장·ADMIN 전용). 역할/상태/파트는 공통코드 드롭다운으로.

---

## 5. 대시보드 — 집계 (Aggregations)

대시보드 위젯은 **미리 집계된 데이터**를 읽습니다. 그 집계를 만드는 API입니다.

### 5-1. 집계 실행(수동/백필) — `POST /api/v1/aggregations` 🔒팀장·ADMIN

특정 월(또는 기간)의 SR 데이터를 기간계에서 읽어 대시보드용으로 집계 적재. **처음 데이터를 채우거나 과거 달을 다시 계산**할 때 사용.

**요청 — 단일 월**
```json
{ "periodYm": "202605" }
```
**요청 — 기간(여러 달 한 번에)**
```json
{ "from": "202601", "to": "202605" }
```
**응답 (201)**: `{ "data": { "periods":["202605"], "count":1 }, "meta": null }`
- 같은 달을 다시 실행해도 안전(멱등 — 덮어씀).
- 매일 자동(스케줄)로도 현재 달이 집계됨.

### 5-2. 집계 이력 — `GET /api/v1/aggregations` 🔒인증
**응답 (200)**
```json
{ "data": [
    { "runId":2,"periodYm":"202605","trigType":"MANUAL","status":"OK","devRows":3,"srRows":1,"startedAt":"2026-06-30 09:29:42","finishedAt":"2026-06-30 09:29:42","msg":null }
  ], "meta": null }
```
**화면 활용(관리 화면)**: "특정 월 재집계" 버튼 → 5-1 호출 → 이력 테이블로 성공/실패(OK/FAIL) 표시.

> 위젯에 데이터가 안 보이면 먼저 그 달을 `POST /aggregations`로 집계해야 합니다.

---

## 6. 대시보드 위젯

### 6-1. 월별 개발량 추이 — `GET /api/v1/dev-volume?unit=team&period=6m&unitId=` 🔒인증

월별 SR **건수**를 SR분류별로. `unit`=team|part|dev, `period`=6m|12m(기본 6m), 파트/개발자 드릴다운 시 `unitId` 필요.

**응답 (200)**
```json
{
  "data": [
    { "periodYm":"202605","monthLabel":"26.05","srCls":"01","srClsName":"개발요청","srCnt":2 },
    { "periodYm":"202605","monthLabel":"26.05","srCls":"02","srClsName":"유지보수","srCnt":1 }
  ],
  "meta": { "unit":"team", "period":"6m", "count":2 }
}
```
**화면 활용**: **막대/라인 차트**. X축=`monthLabel`("26.05"), Y축=`srCnt`, 시리즈=`srClsName`(개발요청/유지보수…). 막대 클릭 → `unit=part&unitId=P01` 재호출로 드릴다운.

### 6-2. 리소스 가동률 — `GET /api/v1/resource?period=202605&unit=team&unitId=` 🔒인증

**응답 (200)**
```json
{
  "data": {
    "periodYm":"202605","unitType":"TEAM","unitId":"ALL",
    "headcount":4,"availHeadcount":3,"availMm":3.0,
    "usedMm":2.69,"overtimeMm":0.69,"utilization":0.897
  },
  "meta": null
}
```
**화면 활용**: **도넛 차트**. 사용중(`usedMm`) vs 가용(`availMm`), 가운데 `utilization`(0.897→89.7%). `headcount`/`availHeadcount`로 "재직 4명 중 개발 3명" 표기.
- 데이터 없으면 400("해당 기간/단위 집계가 없습니다") → 먼저 집계 실행 안내.

### 6-3. 야근 상세 — `GET /api/v1/resource/overtime?period=202605&part=` 🔒인증

**응답 (200)**
```json
{
  "data": [
    { "empno":"E0002","empNm":"이개발","partCd":"P01","planMm":1.44,"overtimeMm":0.44 },
    { "empno":"E0003","empNm":"박개발","partCd":"P02","planMm":1.25,"overtimeMm":0.25 }
  ],
  "meta": { "period":"202605", "count":2, "avgOvertimeMm":0.23 }
}
```
**화면 활용**: 도넛 클릭 시 드릴다운 상세. **개발자별 야근 리스트 테이블**(야근 M/M 내림차순) + KPI 카드 "팀 평균 야근 `avgOvertimeMm`(0.23 M·M)". 야근 0.5 이상 빨강 강조 등.

### 6-4. 주요 SR Top — `GET /api/v1/sr-projects?period=202605&minMm=0.6&type=&page=0&size=5` 🔒인증

계획 M/M(`minMm`) 이상 SR을 큰 순으로, 페이지당 5개.

**응답 (200)**
```json
{
  "data": [
    { "srNo":"SR26000001","titlCntt":"차세대 계좌개설","srTpcd":"01","srTpcdName":"개발요청",
      "totMm":2.3,"empCnt":2,"prchDpcd":"D101","dpcd":"D101" }
  ],
  "meta": { "page":0, "size":5, "totalElements":1, "period":"202605" }
}
```
**화면 활용**: **Top5 카드/테이블**. 행=[SR명(`titlCntt`) | 유형배지(`srTpcdName`) | 계획 M/M(`totMm`) | 담당부서(`prchDpcd`)]. `minMm`/`type` 필터, `page`로 "더 보기".

---

## 7. 전형적인 프론트 흐름 (요약)

1. **앱 진입** → `GET /health`로 연결 확인.
2. **로그인** → `POST /auth/login` → `token` 저장 + `role`로 메뉴 구성. `pwdResetRequired`면 비번 변경 유도.
3. **이후 모든 호출** → 헤더 `Authorization: Bearer <token>` 자동 첨부.
4. **메인 대시보드** → `GET /dev-volume`(막대) + `GET /resource`(도넛) + `GET /sr-projects`(Top5) 3개 호출로 위젯 렌더.
5. **드릴다운** → 위젯 클릭 시 `unit=part/dev` 또는 `resource/overtime`, `sr-projects?page=` 재호출.
6. **관리 기능(팀장·ADMIN)** → 인사/공통코드 CRUD, 집계 재실행. 없는 권한이면 버튼 숨김(+백엔드가 403으로 이중 방어).
7. **토큰 만료(401)** → 로그인 화면으로 유도.

> 데이터가 비어 보이면: 해당 월을 `POST /aggregations`로 먼저 집계했는지 확인.
