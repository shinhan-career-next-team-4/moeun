---
paths:
  - "src/main/java/**"
  - "src/test/java/**"
---

# Spring Modulith 규칙

## 경계

- 모듈은 `com.moeun.backend` 바로 아래의 패키지다. 의존 방향은 `package-info.java`의 `@ApplicationModule(allowedDependencies = ...)`로 고정한다. `{}`는 의존 없음이다.
- 모듈 루트 패키지는 공개 API다. 다른 모듈이 쓰는 서비스, 이벤트, DTO만 둔다. 나머지는 하위 패키지에 두며 밖에서 접근할 수 없다. 하위 패키지(`domain`·`application`·`infrastructure`·`web`)의 책임과 금지 의존은 `ARCHITECTURE.md`가 기준이다.
- 다른 모듈의 엔티티와 리포지토리를 쓰지 않는다. JPA 연관관계는 모듈 안에서만 맺고, 다른 모듈의 행은 ID(`Long`)로 참조한다.
- 순환 의존은 금지다. 역방향 호출이 필요하면 이벤트로 푼다.

## 이벤트

- 이벤트는 `record`로 만들고 과거형으로 이름 짓는다(`PlanChanged`). ID와 값만 담고 엔티티는 담지 않는다.
- 다른 모듈이 받는 이벤트는 발행 모듈의 루트 패키지에 둔다. 모듈 안에서만 쓰는 이벤트는 하위 패키지에 둔다.
- 발행은 트랜잭션 안에서 `ApplicationEventPublisher`로 한다.
- 수신은 `@ApplicationModuleListener`로 한다(커밋 후, 비동기, 새 트랜잭션).
  - 이벤트는 `event_publication`에 기록되고 재기동 시 다시 처리된다. 그래서 리스너는 같은 이벤트를 두 번 받아도 결과가 같아야 한다.
  - 예외: 외부 HTTP를 부르는 리스너는 트랜잭션 없이 돈다(`@ApplicationModuleListener(propagation = NOT_SUPPORTED)`). 호출이 끝날 때까지 DB 커넥션·트랜잭션을 붙들지 않도록 읽기와 저장을 각각 짧은 트랜잭션으로 나눈다.
  - 예외: 응답 전에 끝나야 하는 수신(대화 삭제)은 동기 `@EventListener`로 받고 자체 `@Transactional`에서 처리한다. 실패하면 예외가 발행한 쪽으로 올라간다.
- 같은 트랜잭션이 꼭 필요하고 의존 방향이 허용되면 이벤트 대신 직접 호출한다.

### 이벤트 목록

이벤트를 추가하거나 바꾸면 이 표를 고친다.

| 이벤트 | 발행 | 수신 | 이유 |
|---|---|---|---|
| `plan.PlanChanged {memberId, action(INPUT/APPLY/UNDO), beforeVersionId, afterVersionId, revision}` | plan: 저장·적용·되돌리기 커밋 | calc: 새 버전의 `calc_run(PLAN)` 기록 / plan: 로그 `plan.changed` | calc가 plan에 의존하므로 plan → calc 직접 호출은 순환이 된다. 기록이 늦어도 결과 화면은 최신 기록이 없으면 다시 계산해서 기록한다(ERD Q1-c) |
| `chat` 내부 `ChatAnswerRequested {assistantMessageId, memberId}` | chat: 질문 저장(USER + ASSISTANT `PENDING`) | chat: ai-server `POST /chat` 호출 후 `COMPLETED`/`FAILED`로 갱신 | FR-AI-01 비동기 응답. 재기동 시 `PENDING` 메시지 복구 |
| `auth.MemberLoggedIn {memberId}` | auth: 로그인 성공(쿠키 발급 전) | chat: 그 회원의 chat_message 일괄 삭제(동기) | Q-041 권장안 A. 로그인 직후 F-2 복원이 삭제보다 먼저 도는 경쟁을 없애려고 응답 전에 끝낸다 |
| `auth.MemberLoggedOut {memberId}` | auth: 로그아웃(쿠키 삭제 전) | chat: 그 회원의 chat_message 일괄 삭제(동기) | Q-041 권장안 A. citation은 FK CASCADE, calc_run(CHAT)·계획은 남긴다(D-057) |
