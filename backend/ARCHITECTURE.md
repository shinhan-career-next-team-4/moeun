# backend 구조

모듈 사이 경계는 Spring Modulith가(`ModularityTests`), 모듈 안의 책임은 ArchUnit이(`ArchitectureTests`) 검사한다. 이 문서는 두 검사가 지키는 규칙의 설명이다. 이벤트와 모듈 경계의 세부는 [.claude/rules/modulith.md](.claude/rules/modulith.md).

## 모듈 (`com.moeun.backend.*`)

| 모듈 | 책임 | 테이블 | 의존 가능 |
|---|---|---|---|
| `auth` | 데모 계정, JWT 발급·검증 | member | — |
| `plan` | 계획 조건 스냅샷, 적용·되돌리기, 조건 검증(종목 확인 포트 `EtfProductCatalog`는 calc가 구현) | plan_version, plan | — |
| `refdata` | 기준 데이터와 버전(ETF 가격은 종목별) | data_version, savings_avg_rate, etf_monthly, etf_product_version, calc_param | — |
| `calc` | 계산 엔진, 계산 기록, 내 계획 조회·입력 저장 REST, `/internal/calc` | calc_run | plan, refdata, config(copy) |
| `rag` | 인용 출처 조회(읽기 전용) | source_org, rag_document, rag_chunk, chunk_embedding | — |
| `chat` | 대화 저장, ai-server 비동기 호출 | chat_message, chat_message_citation | calc, rag, plan, auth, config :: copy |

- REST 위치: 내 계획 조회·입력 저장(`GET`/`PUT /api/plan`)은 `calc.web`, 되돌리기(`POST /api/plan/undo`)와 대안·비교 적용(`POST /api/plan/apply`, #68)은 `plan.web`이다. 저장과 계산을 한 트랜잭션으로 묶으려고 부르는 쪽(calc)에 둔다. 대화 적용은 chat 모듈이 `PlanApplyService`를 직접 부른다.
- `config`는 보안 같은 앱 전역 설정이다.
- 의존 방향의 기준은 각 모듈의 `package-info.java`이며, 바꿀 때는 이 표도 같이 고친다.

## 모듈 내부 패키지

| 패키지 | 둔다 | 의존하지 않는다 |
|---|---|---|
| 루트(`plan`, `calc` …) | 다른 모듈이 쓰는 공개 API: 서비스 인터페이스·파사드, 이벤트, 조회용 DTO, 예외 | 자기 모듈의 `web` |
| `domain` | 엔티티, 값 객체, 도메인 규칙, 리포지토리 인터페이스(Spring Data JPA), 외부 기술을 숨기는 포트 인터페이스 | Spring(JPA·Spring Data 제외), Jackson(`tools.jackson`), `web`, `application` |
| `application` | 유스케이스 서비스(트랜잭션 경계·조율), 이벤트 리스너, 공개 API 구현 | `web` |
| `infrastructure` | 외부 기술 어댑터: `JdbcClient` 쿼리, ai-server HTTP 클라이언트, 파일 읽기. `domain`의 포트를 구현한다 | `web` |
| `web` | 컨트롤러, 요청·응답 DTO, 요청 JSON 해석 | `domain`의 리포지토리(서비스를 거친다) |

`infrastructure`는 지금 없다. 새 어댑터는 처음부터 `infrastructure`에 두고, 기존 것은 리팩터링 Issue에서 옮긴다.

#308 시점에 남아 있는 위반 중 `ArchitectureTests`가 **검사하지 않는** 것(리팩터링 대상):

- `JdbcClient`를 `infrastructure` 밖에서 쓴다: `chat.domain.ChatMessageRepository`(이것만 domain이라 동결됨), `calc.application.CalcRunRecorder`, `chat.application.ChatAnswerListener`, `rag.CitationQueryService`
- ai-server HTTP 클라이언트가 `application`에 있다: `chat.application.AiChatClient`
- `application`·루트가 Jackson으로 JSON을 해석한다: `plan.application.PlanService`·`PlanInputValidator`, `chat.application`(`ChatComparisonService`, `ChatAnswerListener`, `AiChatClient` 등), `calc.application`(`ConditionChanges`, `PlanContextProviderImpl`), 루트 `plan.PlanInput`·`PlanApplyService`·`ConditionValidator`, `calc.ChatComparisonQuery`
- 규칙이 서비스에 몰려 있다: `plan.application.PlanService`(306줄), 행동이 거의 없는 `Plan`·`PlanVersion`

이 목록이 줄면 `application`→Jackson 같은 규칙을 `ArchitectureTests`에 추가한다.

## 책임 규칙

### 규칙은 데이터를 가진 엔티티·값 객체에 둔다

- 값을 검사하거나 상태를 바꾸는 규칙은 그 값을 가진 객체의 메서드로 만든다. 서비스가 getter로 값을 꺼내 판단하고 setter로 되돌려 넣지 않는다.
  - 예: "되돌릴 수 있는가", "다음 revision", "이 조건을 덮어쓴 새 스냅샷"은 `Plan`·`PlanVersion`이 답한다.
- 여러 필드가 함께 다니며 규칙을 가지면 값 객체(`record`)로 묶는다. 값 객체는 생성자에서 스스로 검증하고 바뀌지 않는다.
- 엔티티의 setter를 열지 않는다. 상태 변경은 의도를 드러내는 이름의 메서드(`apply`, `undo`, `complete`)로 한다.
- 두 모듈 이상의 데이터가 필요한 규칙만 `application`에 둔다.

### application은 트랜잭션과 조율만 한다

- 서비스 메서드는 "읽기 → 도메인 객체에 시키기 → 저장 → 이벤트 발행"의 순서를 적는 곳이다. `if`로 업무 규칙을 판단하는 코드가 길어지면 도메인으로 옮길 신호다.
- 서비스가 300줄을 넘거나 공개 메서드가 서로 다른 유스케이스를 섞으면 유스케이스별로 나눈다.
- `@Transactional`은 `application`(과 공개 API 구현)에만 붙인다.

### 요청 JSON 해석은 web에서 끝낸다

- 컨트롤러는 요청 본문을 요청 DTO(`record`)로 받거나, `JsonNode`로 받아야 할 때도 web에서 타입 있는 명령 객체로 바꿔서 서비스에 넘긴다.
- `application`·`domain`은 `JsonNode`, `ObjectMapper`, HTTP 상태, 요청·응답 DTO를 모른다. 저장용 JSON 컬럼의 직렬화가 필요하면 `infrastructure`에서 한다(JPA `AttributeConverter`도 Jackson을 쓰면 `infrastructure`에 둔다).
- 응답 DTO 변환(`from(view)`)도 web에서 한다.

### 의존과 객체 생성

- 다른 모듈의 행은 ID로 참조한다. 다른 모듈의 엔티티·리포지토리를 쓰지 않는다.
- 생성자 주입만 쓴다(`@RequiredArgsConstructor` 또는 생성자). 필드 주입을 쓰지 않는다.

## 클래스 이름

- `*Impl`을 쓰지 않는다. 구현 이름은 방식이나 출처를 드러낸다(기존 `EtfProductCatalog` → `RefdataEtfProductCatalog`처럼. 동결된 `PlanContextProviderImpl`은 예컨대 `RecalculatingPlanContextProvider`로 바꾼다).
- 역할별 접미사:

| 접미사 | 패키지 | 뜻 |
|---|---|---|
| `*Controller` | web | HTTP 진입점 |
| `*Request`, `*Response` | web | 요청·응답 DTO |
| `*Service` | application, 루트 | 유스케이스 하나 또는 공개 API |
| `*Listener` | application | 이벤트 수신 |
| `*Repository` | domain(인터페이스), infrastructure(JDBC 구현) | 영속성 |
| `*Client` | infrastructure | 외부 HTTP 호출 |
| `*View`, `*Query` | 루트 | 다른 모듈에 내주는 읽기 모델과 그 조회 API |
| 과거형(`PlanChanged`) | 루트 또는 하위 | 이벤트 |
| `*Exception` | 그 예외를 던지는 계층 | 업무 오류 |

- 엔티티·값 객체에는 접미사를 붙이지 않는다(`Plan`, `PlanVersion`, `Money` ✓ / `PlanEntity` ✗).

## 검사와 예외 동결

`src/test/java/com/moeun/backend/ArchitectureTests.java`가 아래를 검사한다. 스프링 컨텍스트·Docker 없이 빠르게 돌고, Stop 훅(`.claude/hooks/check-java.sh`)이 `ModularityTests`와 함께 돌린다.

| 규칙 | 검사 |
|---|---|
| domain은 Spring에 의존하지 않는다(JPA·Spring Data 제외) | `org.springframework..` 중 `org.springframework.data..` 밖 |
| domain은 Jackson에 의존하지 않는다 | `tools.jackson..`, `com.fasterxml.jackson..`(애너테이션) |
| domain은 web·application에 의존하지 않는다 | `..web..`, `..application..` |
| web은 domain 리포지토리를 직접 쓰지 않는다 | `domain`의 `*Repository` |
| 클래스 이름은 `Impl`로 끝나지 않는다 | 단순 이름 |

- 이 규칙들은 `FreezingArchRule`로 감쌌다. 도입 시점(#308)에 이미 있던 위반은 `src/test/resources/archunit_store/`에 기록되어 통과하고, 새 위반만 실패한다.
- 위반을 고치면 저장소에서 저절로 빠진다(`allowStoreUpdate=true`). 그 변경을 같이 커밋한다.
- 저장소에 위반을 손으로 추가하거나 다시 동결(`freeze.refreeze`)하지 않는다. 새 위반은 코드를 고친다.
- 저장소에 없는 규칙은 처음 돌 때 그 시점의 위반째로 동결된다. 그래서 `storeHoldsExactlyTheFrozenRules`가 `stored.rules`의 키와 `FROZEN_RULES`의 설명이 정확히 같은지 확인한다. 규칙의 `.as(...)` 설명을 바꾸거나 규칙을 추가하면 이 테스트가 실패한다.
- 규칙을 추가·이름 변경할 때는 `FROZEN_RULES`를 고치고, 한 번 돌려 바뀐 `stored.rules`와 새 저장소 파일을 확인한 뒤(옛 키·파일은 지운다) 동결되는 위반을 PR 본문에 적는다.
- 각 규칙이 실제로 위반을 잡는지는 `src/test/java/com/moeun/backend/archfixture/`의 표본으로 확인한다.
