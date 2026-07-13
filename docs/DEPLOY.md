# 폐쇄망 배포 가이드 (백엔드 단독 · Docker)

프론트/화면 없이 **백엔드 API만** 단일 Docker 이미지로 배포한다.
DB(기간계 + app HR/DASH)는 폐쇄망의 기존 Oracle에 **데이터소스로 연결**만 한다(이미지에 DB 미포함).
Testcontainers/JUnit 은 `test` 스코프라 런타임 이미지에 **애초에 들어가지 않는다** — 이미지 빌드 시 `-x test` 로 테스트 단계도 건너뛴다.

## 구성 개요
```
[빌드 머신(인터넷 O)]                         [폐쇄망 가상화 서버(인터넷 X)]
 docker build  ─▶ it-dash:0.0.1              docker load  ◀─ tar 반입
 docker save   ─▶ it-dash-0.0.1.tar.gz  ──▶  docker run --env-file it-dash.env
                                              │
                                              ├─▶ app DB (HR/DASH)   : Flyway 마이그레이션 + CRUD
                                              └─▶ legacy 기간계 DB    : SELECT 전용(장애격리)
```
최종 이미지에 JRE·ojdbc·jar 이 모두 포함되므로 폐쇄망에서 **추가 pull/인터넷 불필요**.

---

## 1) 빌드 머신에서 이미지 생성 (인터넷 필요)
> ⚠️ **사내 SSL 인터셉션(Somansa) 주의** — 컨테이너 내부에서 gradle 이 Maven Central/services.gradle.org 에 붙을 때
> 사내 CA 를 신뢰하지 못해 `PKIX path building failed` 로 빌드가 깨진다(실측 확인됨).
> 그래서 **호스트에서 jar 를 먼저 빌드**(호스트 JDK cacerts 는 사내 CA 신뢰)하고, 이미지는 그 jar 만 담는 방식을 기본으로 한다.

> ⚠️ **아키텍처 일치 필수** — 빌드머신(맥 Apple Silicon=arm64)과 배포서버(대개 x86_64=amd64)가 다르면
> 서버에서 컨테이너가 즉시 죽는다(`platform does not match` → curl 접속 불가). 서버 `uname -m` 확인 후
> 서버 아키텍처용으로 빌드해야 한다. 크로스 빌드 스크립트가 이를 처리한다(아래 권장).

```bash
# 권장: deploy/build-image.sh 가 jar 빌드 + 서버 아키텍처(기본 amd64) 이미지 + tar.gz 까지 처리
PLATFORM=linux/amd64 ./deploy/build-image.sh 0.0.1     # 서버가 x86_64
# (buildx 필요: brew install docker-buildx → ~/.docker/cli-plugins 에 링크)

# 참고: 서버와 빌드머신 아키텍처가 같을 때만 단순 빌드 사용 가능:
#   ./gradlew bootJar -x test && docker build -t it-dash:0.0.1 .
```
> ✅ 위 방식으로 만든 이미지(약 532MB)로 컨테이너 기동→양쪽 DB 연결→`/health`·조회 200, Docker HEALTHCHECK `healthy` 까지 실측 검증됨.
>
> **대안(클린망/CI, 소스부터 한 방에):** `docker build -f Dockerfile.selfcontained -t it-dash:0.0.1 .`
> — 단 사내망에서 쓰려면 빌드 스테이지 컨테이너의 JDK cacerts 에 사내 CA 를 `keytool -importcert` 로 주입해야 한다(그렇지 않으면 위 PKIX 오류).

## 2) 이미지 tar 로 추출
```bash
docker save it-dash:0.0.1 | gzip > it-dash-0.0.1.tar.gz
# 산출물 1개 파일. 사내 반입 절차(승인/USB/파일전송)로 폐쇄망 VM 에 옮긴다.
```

## 3) 폐쇄망 VM 에서 이미지 적재
```bash
gunzip -c it-dash-0.0.1.tar.gz | docker load
docker images | grep it-dash     # it-dash:0.0.1 확인
```

## 4) 환경변수 파일 작성 (`it-dash.env`)
`.env.example` 을 복사해 실제 접속정보로 채운다. **이 파일은 커밋 금지.**
```bash
cp .env.example it-dash.env
vi it-dash.env   # APP_DB_URL / LEGACY_DB_URL / 계정·비번 / GATEWAY_* 등 입력
```
- `APP_DB_URL=jdbc:oracle:thin:@<앱DB호스트>:<포트>/<서비스명>`
- `LEGACY_DB_URL=jdbc:oracle:thin:@<기간계호스트>:<포트>/<서비스명>`
- **게이트웨이 인증(자체 로그인 없음 — X-Access-Token 검증)**:
  - `GATEWAY_JWKS_URL` **(필수)** — 운영 Keycloak JWKS 엔드포인트(`.../realms/<realm>/protocol/openid-connect/certs`). 미설정 시 기동 실패(안전).
  - `GATEWAY_ISSUER` **(필수)** — 운영 Keycloak issuer(`.../realms/<realm>`). 미설정 시 기동 실패.
  - `GATEWAY_AUDIENCE` — 토큰 aud 검증값(기본 `oauth2-proxy`).
  - `GATEWAY_ALLOWED_ROLES` — 우리 서비스 허용 게이트웨이 role(기본 `dev-user`). **미확정 — 운영팀 확인 필요.**
  - `SERVER_CONTEXT_PATH` — 게이트웨이가 발급하는 서비스 경로명(예: `/it-dash`). **운영팀 발급값으로 설정.**
- `FLYWAY_ENABLED` 는 5번 참고.

## 5) 컨테이너 실행
```bash
docker run -d --name it-dash \
  --restart unless-stopped \
  -p 8080:8080 \
  --env-file it-dash.env \
  it-dash:0.0.1
```
- 기본 프로파일 `prod`(이미지에 내장). 컨테이너 힙은 메모리 75%로 자동 조정.
- 재부팅 후 자동 기동: `--restart unless-stopped`.

## 6) 검증
```bash
docker logs -f it-dash                        # 기동 로그 / Flyway 결과
curl http://<VM_IP>:8080/api/v1/health        # {"data":{"status":"UP"}} 기대
# Swagger: http://<VM_IP>:8080/swagger-ui/index.html  (인증 API는 X-Access-Token 헤더 필요 — 게이트웨이 경유 접속 시 자동 첨부)
```

---

## Flyway (app DB 스키마)
**운영 기본값 = 꺼짐(`FLYWAY_ENABLED=false`)** — 이미 만들어 둔 스키마에 "붙기만" 하는 시나리오. 기존 스키마를 건드리지 않는다.

| 상황 | 설정 | 결과 |
|---|---|---|
| **스키마를 이미 만들어 둠**(권장/기본) | `FLYWAY_ENABLED=false` | 마이그레이션 미실행, 기존 스키마에 연결만 |
| 빈 DB에 앱이 생성 | `FLYWAY_ENABLED=true` | 기동 시 Flyway 가 HR/DASH 생성/시드(앱 계정 DDL 권한 필요) |

> **legacy 기간계 DB 에는 Flyway 가 절대 실행되지 않는다**(app 데이터소스에만 적용). 기간계는 SELECT 전용 규칙 유지.

## 운영 주의
- **기간계 down 내성**: 기동 시 기간계가 죽어 있어도 앱은 정상 기동한다(`initialization-fail-timeout=-1`). 메인 대시보드는 app DB 기반이라 동작. 상세 드릴다운만 영향.
- **시크릿/필수 env**: DB 비번·`GATEWAY_JWKS_URL`·`GATEWAY_ISSUER` 는 env 로만 주입(이미지·소스에 미포함). 운영은 `app.gateway.enabled=true` 고정 — env 로 인증을 끌 수 없다.
- **네트워크 노출**: 앱은 `0.0.0.0:8080` 바인딩. 폐쇄망 방화벽/보안그룹으로 접근 대상 제한 권장.
- **타임존**: 컨테이너 `Asia/Seoul` 고정.
- **업그레이드**: 새 버전은 태그만 올려(`it-dash:0.0.2`) 1~5번 반복 후 `docker stop/rm` → `docker run`.

## 참고: 사내 레지스트리(Harbor/Nexus)가 있으면
`docker save/load` 대신 빌드 머신에서 `docker tag it-dash:0.0.1 <registry>/it-dash:0.0.1 && docker push ...`,
폐쇄망 VM 에서 `docker pull <registry>/it-dash:0.0.1` 로 대체 가능.
