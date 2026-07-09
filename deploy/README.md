# it-dash 백엔드 배포 가이드 (폐쇄망 · Docker · 프론트 없음)

이 폴더(`deploy/`)만 있으면 배포가 된다. 프론트/화면은 배포하지 않고, **백엔드 API + 내장 Swagger UI**만 올린다.
DB는 이미지에 없고, 폐쇄망의 기존 Oracle(app HR/DASH + 기간계)에 **접속정보만 넣어 연결**한다.
Flyway는 기본 꺼짐 — **이미 만들어 둔 스키마에 붙기만** 한다.

---

## 0. 폴더 구성
```
deploy/
├── README.md               ← 이 문서
├── build-image.sh          ← [빌드머신] jar→이미지→tar.gz 생성
├── load-and-run.sh         ← [서버] 이미지 적재→컨테이너 실행
├── docker-compose.yml      ← [서버] compose 로 실행하는 대안
├── it-dash.env.example     ← 접속정보/시크릿 템플릿
└── it-dash-0.0.1.tar.gz    ← 실제 배포 이미지(빌드 시 생성, 용량 커서 git 미포함)
```

---

## 1. 배포 서버(가상화)에 미리 깔려 있어야 하는 것

| 항목 | 필요 | 확인 명령 |
|---|---|---|
| **Docker Engine** | **필수**(유일한 필수 요소) | `docker --version` |
| docker compose 플러그인 | 선택(compose 방식 쓸 때) | `docker compose version` |
| Oracle 클라이언트 / instantclient | **불필요** — thin JDBC 드라이버가 이미지에 내장 | — |
| Java(JDK/JRE) | **불필요** — JRE 이미지에 내장 | — |

**아키텍처(중요) — 이미지 아키텍처 = 서버 아키텍처**
서버에서 `uname -m` 확인: `x86_64` → **linux/amd64**(대부분), `aarch64` → **linux/arm64**.
빌드머신(맥 Apple Silicon 등)과 서버 아키텍처가 다르면, 빌드 시 `PLATFORM` 을 서버에 맞춰야 한다(2절).
안 맞으면 서버에서 `WARNING: platform ... does not match` 뜨고 컨테이너가 **즉시 죽어 접속이 안 된다**(curl (7)).

**네트워크(방화벽) — 이게 실제로 제일 중요**
- **아웃바운드**: 배포서버 → app Oracle `host:port`, 기간계 Oracle `host:port` 로 TCP 접속 열려야 함(보통 1521).
- **인바운드**: 사용자/프론트개발자 PC → 배포서버 `앱포트`(기본 8080, 원하는 값 지정 가능) 열려야 함.
- 이미지 자체는 폐쇄망 반입 후 인터넷 불필요(JRE·드라이버·jar 다 포함).

> Docker 미설치 서버라면: 사내 배포 정책에 맞는 방식(오프라인 rpm/deb, 사내 레지스트리 등)으로 Docker Engine을 먼저 설치해야 한다. (설치는 인프라팀/서버 담당 영역)

---

## 2. [빌드 머신] 이미지 만들기 — 인터넷 되는 곳에서 1회

> ⚠️ 사내 SSL 인터셉션 때문에 **컨테이너 안에서 gradle 다운로드는 실패**한다. 그래서 사내 CA를 신뢰하는 **호스트(개발 Mac 등)에서 jar를 빌드**한 뒤 이미지를 만든다. `build-image.sh`가 그 순서를 자동으로 한다.

```bash
# 프로젝트 루트에서 (JDK21 준비: 필요시 export JAVA_HOME=...)
# 서버가 x86_64면 기본값(linux/amd64) 그대로:
./deploy/build-image.sh 0.0.1
# 서버가 ARM(aarch64)이면:
PLATFORM=linux/arm64 ./deploy/build-image.sh 0.0.1
```
결과물: `deploy/it-dash-0.0.1.tar.gz` (약 125MB) — 이 파일 하나가 배포 이미지다.

> 크로스 아키텍처(맥 arm64 → 서버 amd64) 빌드에는 **docker buildx** 가 필요하다. 없으면 스크립트가 설치법을 안내한다:
> `brew install docker-buildx && mkdir -p ~/.docker/cli-plugins && ln -sf "$(brew --prefix)/lib/docker/cli-plugins/docker-buildx" ~/.docker/cli-plugins/docker-buildx`

---

## 3. 폐쇄망 서버로 파일 반입
아래 3개를 사내 승인 채널(파일전송/USB 등)로 서버의 한 폴더(예: `/opt/it-dash/`)에 옮긴다.
```
it-dash-0.0.1.tar.gz     # 이미지
load-and-run.sh          # 실행 스크립트 (또는 docker-compose.yml)
it-dash.env.example      # 환경변수 템플릿
```

---

## 4. Oracle 데이터소스 설정 (배포 서버에서)
`it-dash.env.example` 를 복사해 실제 접속정보를 넣는다. **이미지 재빌드 없이 여기만 바꾸면 된다.**
```bash
cd /opt/it-dash
cp it-dash.env.example it-dash.env
vi it-dash.env
```

### 4-A. 방식① Easy Connect (권장·간단)
```properties
APP_DB_URL=jdbc:oracle:thin:@10.20.30.40:1521/ORAPDB     # 호스트:포트/서비스명
APP_DB_USERNAME=appuser
APP_DB_PASSWORD=실제비번
LEGACY_DB_URL=jdbc:oracle:thin:@10.20.30.41:1521/LEGACYPDB
LEGACY_DB_USERNAME=legacyuser
LEGACY_DB_PASSWORD=실제비번
JWT_SECRET=$(openssl rand -base64 48 로 생성한 값)
ADMIN_USERNAME=admin
ADMIN_PASSWORD=강한비번
FLYWAY_ENABLED=false
```
- `SID` 만 아는 경우: `jdbc:oracle:thin:@호스트:포트:SID` (슬래시 대신 콜론).

> **서비스명(SERVICE_NAME)은 정확히 써야 한다.** 틀리면 `ORA-12514` 로 접속 거부됨.
> 확인법: ① DBA/기존 tnsnames.ora 의 `SERVICE_NAME=` ② DB서버에서 `lsnrctl status`(등록 서비스 목록)
> ③ SQL `SELECT value FROM v$parameter WHERE name='service_names';` / `SELECT SYS_CONTEXT('USERENV','SERVICE_NAME') FROM dual;`
> (PDB면 `SELECT name FROM v$pdbs;`).
> **SERVICE_NAME 은 URL 에서 `/서비스명`, SID 는 `:SID`** 로 구분자가 다르다.

### 4-B. 방식② tnsnames.ora 사용
1) 서버 폴더에 `tnsadmin/tnsnames.ora` 를 둔다(별칭 정의).
2) `it-dash.env` 에:
```properties
APP_DB_URL=jdbc:oracle:thin:@APP_ALIAS
LEGACY_DB_URL=jdbc:oracle:thin:@LEGACY_ALIAS
ORACLE_NET_TNS_ADMIN=/tnsadmin
```
3) 실행 시 그 폴더를 컨테이너에 마운트한다(compose는 volumes 주석 해제, docker run은 `-v $(pwd)/tnsadmin:/tnsadmin:ro` 추가).

> **비번·URL·계정은 전부 이 env 파일에서 배포 시점에 설정**한다. 소스/이미지에는 안 들어간다.

---

## 5. 실행

### 방식 A) 스크립트
```bash
chmod +x load-and-run.sh
PORT=8080 ./load-and-run.sh 0.0.1      # 외부 포트를 바꾸려면 PORT=9090 등
```

### 방식 B) docker compose
```bash
gunzip -c it-dash-0.0.1.tar.gz | docker load     # 이미지 적재(최초 1회)
docker compose up -d
```

### 방식 C) docker run 직접
```bash
gunzip -c it-dash-0.0.1.tar.gz | docker load
docker run -d --name it-dash --restart unless-stopped \
  -p 8080:8080 --env-file it-dash.env \
  it-dash:0.0.1
```
> **포트는 직접 정한다** — `-p 원하는포트:8080`. IP는 서버 IP를 그대로 쓴다(앱은 0.0.0.0 바인딩).

---

## 6. 배포 확인 (프론트 없이도 눈으로 확인)
```bash
# 1) 살아있는지 (무인증)
curl http://localhost:8080/api/v1/health
#   → {"data":{"status":"UP"...}}

# 2) 브라우저로 Swagger UI 열기 = 배포 확인 페이지
http://<서버IP>:8080/swagger-ui/index.html
```
Swagger UI에서:
1. `POST /api/v1/auth/login` 실행(`{"empno":"admin","password":"<ADMIN_PASSWORD>"}`) → 응답 `data.token` 복사
2. 우측 상단 **Authorize** 에 토큰 붙여넣기
3. 모든 조회 API를 버튼 클릭으로 실제 호출·응답 확인(설명·예시 내장)

> `/swagger-ui`, `/v3/api-docs`, `/api/v1/health` 는 인증 없이 열린다. 나머지 조회 API는 로그인 토큰 필요(역할 구분 없이 조회 가능).

---

## 7. 운영 명령
```bash
docker logs -f it-dash            # 로그 실시간
docker ps                         # 상태/헬스(healthy) 확인
docker restart it-dash            # 재시작
docker stop it-dash               # 중지
docker rm -f it-dash              # 삭제
```
서버 재부팅 후에도 `--restart unless-stopped` 로 자동 기동된다.

### 업그레이드(새 버전)
```bash
# 빌드머신: ./deploy/build-image.sh 0.0.2  → it-dash-0.0.2.tar.gz 반입
gunzip -c it-dash-0.0.2.tar.gz | docker load
PORT=8080 ./load-and-run.sh 0.0.2         # 기존 컨테이너 자동 교체
```

---

## 8. 문제 해결

**접속 검증은 기동 로그로** — `docker logs it-dash | grep -iE "ORA-|Started|HikariPool"`
| 로그 | 의미 |
|---|---|
| `ORA-12514` | **서비스명 틀림**(리스너가 모르는 서비스) |
| `ORA-12541` / `연결 거부` | 리스너 없음 = **호스트·포트 틀림 또는 방화벽** |
| `ORA-01017` | **계정/비번 틀림** |
| `Started DashApplication` + `app-pool ... Added connection` | **정상 접속** |

| 증상 | 원인/조치 |
|---|---|
| `WARNING: platform ... does not match` + 실행 직후 `curl (7) failed to connect` | **아키텍처 불일치**(예: arm64 이미지를 amd64 서버에). `docker ps -a` 하면 컨테이너가 Exited. 서버 `uname -m` 확인 후 맞는 `PLATFORM` 으로 재빌드(2절). |
| 기동은 되는데 조회 500/DB 오류 | `it-dash.env` 의 URL/계정/비번 확인, 서버→Oracle 방화벽(아웃바운드) 확인 |
| 기동 로그에 legacy 관련 에러만 | 기간계 down 이어도 앱은 뜬다(장애격리). 메인 대시보드는 정상, 상세 드릴다운만 영향 |
| `curl health` 접속 안 됨 | 컨테이너 상태 `docker ps`, 포트 매핑(-p), 인바운드 방화벽 확인 |
| 포트 충돌 | `-p 다른포트:8080` 로 변경 |
| 기동 실패 + JWT/ADMIN 관련 | prod는 `JWT_SECRET`/`ADMIN_*` 미설정 시 일부러 실패(안전). env 채웠는지 확인 |
| 스키마 관련 에러 | `FLYWAY_ENABLED=false` 인지 확인(기존 스키마에 붙기만) |
