---
description: 하나 이상의 feature를 TDD(Red→Green→Refactor)로, 독립 feature는 병렬로 구현
argument-hint: <feature 이름들, 예: devvolume resource srproject>
---

# /tdd-feature

대상 feature: **$ARGUMENTS**

아래 절차를 그대로 따른다. 너(메인 세션)는 **오케스트레이터**이며 직접 대량 구현하지 말고 서브에이전트에 위임한다.

## 0. 사전 정렬 (공통부터)
- `CLAUDE.md`, `docs/ARCHITECTURE.md`, `docs/API.md`를 읽는다.
- 여러 feature가 공유하는 `common`(응답 envelope, ProblemDetail 핸들러, 예외)과 `config`(두 DataSource/MyBatis/Hikari)가 아직 없으면 **먼저 한 번에** 합의·구축한다. (병렬 분기 전에 충돌 요소 제거)

## 1. Red — 테스트 먼저
- 각 feature에 대해 `test-writer` 서브에이전트를 호출해 **실패하는 테스트**를 작성한다.
  - 계산/경계값(M/M 환산, 야근 1.0 경계, Top5 100h 경계, 가동률 분모 0), 컨트롤러 계약, 기간계 매퍼 SELECT-only 검증 포함.
- 테스트가 의도대로 실패(Red)하는지 확인한다.

## 2. Green — 구현 (독립 feature는 병렬)
- feature들이 **서로 독립적**이면 `backend-implementer` 서브에이전트를 **여러 개 병렬로** 띄워 동시에 구현한다.
  - 단, 같은 파일(`common`/`config`/공유 매퍼 XML)을 동시에 건드리는 작업은 병렬 금지 → 순차.
- 각 implementer는 해당 feature의 테스트를 **녹색**으로 만드는 최소 구현만.

## 3. Refactor
- 테스트 녹색을 유지하며 중복 제거/네이밍 정리. 다시 전체 테스트.

## 4. 검증
- `./gradlew build` 통과 확인.
- 이어서 `/review-all`을 실행해 다각화 리뷰를 받는다.

## 보고
- feature별로: 작성된 테스트, 구현 파일, 통과 상태, 남은 TODO/가정을 표로 정리한다.

> 비용이 빡빡하면 2단계 병렬 대신 순차로 진행한다(토큰 절약).
