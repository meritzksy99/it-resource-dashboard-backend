---
description: 4개 리뷰 서브에이전트를 병렬로 띄워 코드/DB/보안/아키텍처를 동시에 점검하고 통합
argument-hint: (선택) 리뷰 대상 경로나 feature 이름
---

# /review-all

리뷰 대상: **$ARGUMENTS** (비어 있으면 최근 변경분 전체)

## 절차
1. 리뷰 범위를 정한다(인자가 없으면 최근 git diff 또는 최근 변경 파일).
2. 아래 4개 리뷰 서브에이전트를 **병렬로** 호출한다. 모두 **읽기 전용**이라 안전하게 동시 실행 가능하다.
   - `code-reviewer` — 가독성/설계/버그/스프링 관용
   - `db-query-reviewer` — 기간계 쓰기 금지 위반, 타임아웃/풀/풀스캔, 바인드 변수
   - `security-reviewer` — injection/시크릿/입력검증/개인정보 노출
   - `architecture-reviewer` — 레이어 경계, DataSource 분리, 읽기 경로 정합성, 배치 멱등성
3. 4개 결과를 모아 **통합 리포트**를 만든다.

## 통합 리포트 형식
```
# 다각화 리뷰 통합 결과 — <대상>

## Critical (머지 전 반드시 수정) — 총 N건
- [관점] [파일:라인] 요약 — 조치
  (4개 관점에서 올라온 Critical을 한 곳에 모은다)

## Warning — 총 N건
- [관점] ...

## Suggestion
- [관점] ...

## 관점별 한줄 요약
- code: ...
- db: ...
- security: ...
- architecture: ...

## 판정
Critical 0건 → 통과 / 1건 이상 → 수정 후 재리뷰
```

- 같은 이슈를 여러 관점이 지적하면 합치되 어느 관점들이 지적했는지 표시한다.
- 판정이 "수정 필요"면 우선순위(Critical→Warning) 순으로 처리 계획을 제시한다.
