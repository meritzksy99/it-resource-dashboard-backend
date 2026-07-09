# syntax=docker/dockerfile:1
# ──────────────────────────────────────────────────────────────────────────
# 런타임 전용 이미지 (권장 — 특히 사내 SSL 인터셉션 환경).
# 호스트에서 미리 빌드한 실행가능 jar 를 담기만 한다(컨테이너 내부 gradle 다운로드 없음).
#
#   사전 빌드(호스트 = 사내 CA 신뢰 → 의존성 다운로드 정상):
#     ./gradlew bootJar -x test
#   이미지 빌드:
#     docker build -t it-dash:0.0.1 .
#
# Testcontainers/JUnit 은 test 스코프라 jar/이미지에 포함되지 않는다.
# (인터넷 되는 CI/클린망에서 소스부터 한 방에 빌드하려면 Dockerfile.selfcontained 사용)
# ──────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# 헬스체크용 curl (Ubuntu apt=HTTP, TLS 인터셉션 영향 적음). 베이스 이미지 pull 은 도커 데몬이 담당.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

RUN groupadd --system app && useradd --system --gid app --home-dir /app app

ARG JAR_FILE=build/libs/*.jar
COPY ${JAR_FILE} /app/app.jar
RUN chown -R app:app /app
USER app

EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -Duser.timezone=Asia/Seoul"
ENV SPRING_PROFILES_ACTIVE=prod

HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=5 \
  CMD curl -fsS http://localhost:8080/api/v1/health || exit 1

ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar /app/app.jar"]
