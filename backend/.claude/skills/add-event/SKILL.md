---
name: add-event
description: 모듈 사이에 도메인 이벤트를 추가하거나 바꿀 때 사용한다. 발행, 수신, 의존 선언, 이벤트 목록 갱신, 테스트까지 처리한다.
---

# 이벤트 추가

1. `.claude/rules/modulith.md`의 이벤트 목록을 본다. 이미 있는 이벤트로 해결되면 새로 만들지 않는다.
2. 발행 모듈의 루트 패키지에 과거형 `record`를 만든다. ID와 값만 담는다. 모듈 안에서만 쓰는 이벤트는 하위 패키지에 둔다.
3. 발행 서비스의 트랜잭션 안에서 `ApplicationEventPublisher.publishEvent`를 호출한다.
4. 수신 모듈의 `application`에 `@ApplicationModuleListener` 메서드를 만든다. 같은 이벤트를 두 번 받아도 결과가 같게 만든다(재기동 시 재처리된다).
5. 수신 모듈의 `allowedDependencies`에 발행 모듈이 있는지 확인한다. 이벤트 타입을 참조하는 것도 의존이다.
6. 이벤트 목록 표에 발행 시점, 수신자, 이유를 적는다.
7. 테스트를 쓴다. 발행은 `AssertablePublishedEvents`로, 수신은 `Scenario.publish(...).andWaitForStateChange(...)`로 검증한다. 마지막에 `ModularityTests`를 돌린다.
