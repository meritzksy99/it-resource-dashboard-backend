# CLAUDE.md — IT 개발팀 리소스 현황 대시보드 (백엔드)

> 이 파일은 Claude Code가 매 세션 시작 시 **자동으로 읽는** 프로젝트 규칙서다.
> 여기 적힌 규칙은 반드시 지켜야 하는 제약(MUST)이다. API 상세는 `docs/API.md` 참조.

---

## 1. 프로젝트 개요

개발팀의 **개발량 추이 · 리소스(M/M) 가동률 · 주요 SR Top 5**를 한 화면에 보여주는
내부용 대시보드의 **백엔드**. 화면은 `① 메인 요약 → ② 클릭 시 상세 드릴다운`.

---

## 2. 기술 스택 (고정)

- **Java 21**, **Spring Boot 3.x**, Gradle(Groovy DSL)
- 영속성: **MyBatis** (JPA 아님)
- DB: **Oracle 2개** — 기간계(조회 전용) / DB2(읽기+쓰기)
- HikariCP(DataSource별 분리), 테스트: JUnit5 + AssertJ + Mockito
- API 문서/테스트: **springdoc-openapi(Swagger UI)** + **Postman 컬렉션**
- 검증 안 된 라이브러리 임의 추가 금지(필요하면 먼저 제안).

### 배포/범위 — 백엔드 단독
- 이 프로젝트는 **백엔드만** 만들고 **백엔드만 배포**한다. 프론트엔드 화면(SPA/뷰/템플릿)은 **만들지 않는다.**
- API 동작 확인은 **Swagger UI(`/swagger-ui`)** 로 한다(별도 화면 코딩 X). 프론트 개발자/타인이 직접 호출해 테스트할 수 있게 한다.
- 함께 관리하는 검증 자산: `docs/postman_collection.json`(Postman 컬렉션), `*.http` 파일(선택).
- 테스트 단계에 외부(같은 망의 타인)에서 호출 가능해야 하므로 `server.address=0.0.0.0` 바인딩을 명시한다(운영 배포 시 방화벽/네트워크 정책에 맞춰 재검토).

### 테스트 DB 버전 정책 (운영 19c)
- 운영 기간계/DB2는 **Oracle 19c**. 로컬/단위 테스트는 **Oracle 23ai Free**(도커, Testcontainers) 사용 허용 — 빠른 개발용.
- 단, **19c에 없는 23ai 전용 기능/문법은 사용 금지**(예: `BOOLEAN` 컬럼 타입, 23ai 전용 식별자 길이/JSON·벡터 기능 등). "호환"이 "동일"은 아니다.
- 23ai로 테스트 시 가능하면 `COMPATIBLE=19.0.0` 으로 19c 동작에 맞춘다.
- **머지 전 통합 테스트는 19c와 동일 버전 컨테이너로 한 번 더** 검증한다(이중 안전장치).

---

## 3. 아키텍처 핵심 + 안전 규칙 (가장 중요 — MUST)

### 3.1 DataSource는 2개, 역할이 다르다
| 이름 | 대상 | 권한 | 용도 |
|---|---|---|---|
| `legacyDataSource` (기간계) | 운영 기간계 Oracle | **SELECT 전용** | 상세화면 **실시간 조회**, 일배치 원천 읽기 |
| `appDataSource` (DB2) `@Primary` | 사내 Oracle | CRUD | 대시보드 **집계 결과 저장·조회**, 인사/마스터 |

직접 `@Bean`으로 두 세트 구성(자동설정 의존 X): 각자
SqlSessionFactory · TxManager · `@MapperScan`(`mapper.legacy` / `mapper.app`) · XML 폴더(`mapper/legacy` / `mapper/app`) 분리.

### 3.2 기간계(legacy) 절대 규칙
- 기간계 매퍼에 **INSERT/UPDATE/DELETE/MERGE/DDL 절대 금지. SELECT만.**
- 기간계 조회는 `@Transactional(value="legacyTxManager", readOnly=true)`. DB2 쓰기는 `@Transactional("appTxManager")`.
- 기간계 Hikari: `read-only:true`, 풀 작게(≤8), connection-timeout 짧게, **statement timeout 5초**.
- SQL 값 주입은 **바인드 변수 `#{}` 만**. `${}`는 화이트리스트 검증된 정렬/컬럼명만.
- 기간계가 죽어도 메인 대시보드(DB2 기반)는 정상 동작해야 한다(장애 격리).

### 3.3 읽기 경로 두 가지 (혼동 금지)
- **메인 요약**(월별 개발량·가동률·Top5) → **DB2 집계 테이블**에서 읽는다(빠름). 기간계 직접 호출 금지.
- **상세 드릴다운**(실시간성 필요) → **기간계 실시간** 조회.

### 3.5 DB 구성 & 신규 테이블 규칙
- **기간계**: 기존 운영 DB. 우리가 만들지 않는다. **테이블 생성/변경 금지, SELECT만.**
 - 기간계테이블 역할을 하는 테스트 디비도 만들어서 확인
  - 기간계 테이블·컬럼명은 **추측하지 말고 반드시 `/ora-db` 명령으로 실제 스키마(테이블/컬럼 메타)를 조회해 확인**한 뒤 쿼리를 작성한다. (`/ora-db`는 기간계 메타 전용, 실제 데이터·DDL은 제공 안 함.)
  - 조회로 확인한 컬럼명/타입에 맞춰 매퍼와 DTO를 만든다. 확인 안 된 컬럼명을 임의로 쓰지 않는다.
- **DB2(신규, Oracle 19c)**: 우리가 테이블을 **신규 생성**. 운영은 DBA가 만든 DB에 접속, 테스트는 도커(Testcontainers)로 동일 DDL 적용.
- **DB2 DDL은 버전 파일로 관리**: `db/migration/VNNN__설명.sql` (기존 파일 수정 금지, 변경은 새 파일로). 운영/테스트 동일 적용.
- **네이밍 (Oracle 관례 = 대문자 SNAKE_CASE)**
  - 테이블: 영어 + **용도에 맞는 이름** + 도메인 접두어. 예) 대시보드 집계 `DASH_*`, 인사 `HR_*`, 코드 `CD_*`.
  - 신규 DB2 컬럼명은 `/ora-db`로 본 **기간계 회사 컨벤션과 유사하게** 맞춘다(이름 스타일·약어 일관성).
  - 제약/인덱스: `PK_<테이블>`, `UK_<테이블>_<컬럼>`, `FK_<자식>_<부모>`, `IX_<테이블>_<컬럼>`.
- **공통 컬럼 표준** (신규 테이블에 일관 적용)
  - PK는 `<도메인>_ID` 또는 의미 키. 감사 컬럼 `CREATED_AT`, `CREATED_BY`, `UPDATED_AT`, `UPDATED_BY`.
  - 플래그는 **`CHAR(1) 'Y'/'N'`** (19c엔 `BOOLEAN` 컬럼 타입 없음). 수치·금액 `NUMBER`, 날짜시간 `DATE`/`TIMESTAMP`.
  - 코드성 값은 `CD_*` 코드테이블 또는 `CHECK` 제약으로 통제(매직 문자열 금지).
- **19c 호환 DDL만** 사용(23ai 전용 문법 금지 — 2장 테스트 DB 정책과 동일).

---
### 3.4 일배치 (집계)
- 기간계 read → DB2 write 단방향. period 키 **MERGE upsert**로 멱등성 보장(재실행 안전).
- MVP는 `@Scheduled`. 실패는 롤백+로깅, API 서빙과 분리.

---

## 4. 계산/환산 규칙 (요건서 — 테스트로 경계값 고정 대상)

- `1 M/M = 166시간` → 설정값 `app.mm.hours-per-month`. **하드코딩 금지.**
- 가용 M/M = 개발 가능 인원 수 × 1 M/M (팀장 등 비개발 인원 제외).
- 사용중 M/M = 이번달 진행중 SR 계획 M/M 합. 가동률 = 사용중 ÷ 가용 (분모 0 방어).
- 야근 M/M(개발자) = max(투입 M/M − 1.0, 0). 팀 평균 야근 = Σ(야근) ÷ 개발 인원.
- Top 5 기준 = 계획 M/M 합 ≥ 100시간(설정값), 계획 M/M 큰 순 정렬.
- 테스트 경계값: 0 / 1.0 정확히 / 1.0 초과 / 100h 경계.

---

## 5. 개발 방식 — 테스트 주도(TDD)가 기본

기능 구현은 항상 **Red → Green → Refactor** 순서를 따른다.
1. **Red**: `test-writer` 서브에이전트로 실패하는 테스트부터 작성(계산/경계, 컨트롤러 계약, 기간계 SELECT-only 검증).
2. **Green**: 테스트를 통과시키는 최소 구현.
3. **Refactor**: 테스트 녹색 유지하며 정리.

- 새 계산/집계 로직은 **반드시 테스트 먼저**. 테스트 없이 비즈니스 로직 머지 금지.
- 워크플로는 `/tdd-feature <feature...>` 커맨드를 사용한다.

---

## 6. 다각화 리뷰

구현이 끝나면 `/review-all` 을 실행해 여러 관점을 **병렬**로 점검한다.
- `code-reviewer` — 가독성/설계/버그/스프링 관용
- `security-reviewer` — SQL injection / 시크릿 노출 / 입력검증 / 개인정보(인사정보) 노출

리뷰어는 **읽기 전용**(코드 수정 안 함), Critical/Warning/Suggestion 3단계로 보고한다.
**Critical 0건**이어야 머지한다.

---

## 7. API 규약

상세는 `docs/API.md`. 핵심: 베이스 `/api/v1`, 리소스 복수형 kebab-case,
드릴다운은 쿼리 파라미터(`?unit=team|part|developer&period=6m|12m`),
응답 envelope `{ "data":..., "meta":... }`, 에러는 **RFC 7807 `ProblemDetail`**,
모든 엔드포인트 springdoc 문서화.

**헬스 체크**: 프론트 개발자/타인이 연결을 확인하도록 `GET /api/v1/health` 하나를 둔다.
- 인증 없이 호출 가능, 응답은 가벼운 `{ "status": "UP", ... }`.
- 두 DataSource 상태를 같이 표기하되 **기간계가 DOWN이어도 앱 전체는 UP**(메인 대시보드는 DB2 기반이므로). 기간계 상태는 별도 필드로만 노출.
- 기간계 헬스 조회는 가볍게(`SELECT 1 FROM DUAL`) + 짧은 타임아웃.

---

## 8. 코딩 컨벤션 (요약)

- DTO는 `record`. 컨트롤러는 DTO만 반환(엔티티/row 누출 금지). 생성자 주입만(필드 주입 금지).
- 매직넘버 금지 — 환산값/기준값은 `@ConfigurationProperties`.
- 예외 → `@RestControllerAdvice` → `ProblemDetail`. 로깅은 SLF4J(기간계 쿼리 실행시간 로깅).
- DB 접속정보/비밀번호는 **소스에 커밋 금지**(환경변수/외부설정). 프로파일 `local/dev/prod` 분리.

---

## 9. Definition of Done

- [ ] Red→Green→Refactor로 개발됨, 계산/경계 테스트 통과
- [ ] 기간계 매퍼에 쓰기 SQL 없음(read-only)
- [ ] DTO 경계 유지, ProblemDetail 에러 처리, springdoc 문서화
- [ ] `/review-all`의 Critical 0건
- [ ] `./gradlew build` 통과

---

## 10. 사용 가능한 도구

서브에이전트(`.claude/agents/`): `test-writer`, `code-reviewer`, `security-reviewer`
커맨드(`.claude/commands/`): `/tdd-feature`, `/review-all`

메인 세션은 오케스트레이터로서 TDD 순서를 지키고, 구현 후 `/review-all`을 돌린다.
