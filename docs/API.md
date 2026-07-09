# docs/API.md — REST API 규약 & 엔드포인트

## 공통 규약

- 베이스: `/api/v1`
- 응답 envelope:
  ```json
  { "data": { ... }, "meta": { ... } }
  ```
  목록은 `meta`에 `{ "page", "size", "totalElements", "period" }` 등.
- 에러: **RFC 7807 `application/problem+json`** (`ProblemDetail`).
  ```json
  { "type":"about:blank", "title":"Bad Request", "status":400,
    "detail":"period 파라미터는 6m 또는 12m 여야 합니다.", "instance":"/api/v1/dev-volume" }
  ```
- 날짜: ISO-8601. 월 레이블은 `monthLabel`(`"26.05"`) 필드로 함께 제공.
- 모든 엔드포인트 springdoc 문서화 필수.
## 0. 헬스 체크 (프론트 개발자 연결 확인용)

| Method | Path | 설명 |
|---|---|---|
| GET | `/api/v1/health` | 서버 가동 확인. 인증 불필요. |

응답 `data` 예:
```json
{ "status":"UP",
  "components":{ "app":"UP", "legacy":"UP" },
  "timestamp":"2026-06-30T09:00:00Z" }
```

## API 테스트 수단
- **Swagger UI**: `/swagger-ui` (springdoc). 브라우저에서 직접 호출/테스트. 화면은 별도로 만들지 않는다.
- **Postman 컬렉션**: `docs/postman_collection.json` 으로 관리. 새 엔드포인트 추가 시 컬렉션도 같이 갱신한다.
  - 환경변수 `{{baseUrl}}`(예: `http://<서버IP>:8080`)을 사용해 다른 사람이 망 내에서 바로 호출 가능하게 한다.


## 상태코드 규칙
- 200 조회 성공 / 201 생성 / 204 본문 없는 성공
- 400 검증 실패 / 404 미존재 / 409 충돌 / 500 서버오류
