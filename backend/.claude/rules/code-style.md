---
paths:
  - "**/*.java"
---

# 코드 스타일 (네이버 컨벤션, 스페이스 4칸)

- 기준: [네이버 캠퍼스 핵데이 Java 컨벤션](https://naver.github.io/hackday-conventions-java/). 단, 들여쓰기는 탭 대신 **스페이스 4칸**이다.
- 검사: `./gradlew checkstyleMain checkstyleTest`. 위반이 하나라도 있으면 빌드가 실패한다(`maxWarnings = 0`). 설정 파일은 `config/checkstyle/`에 있다.
- Checkstyle이 잡는 것: 줄 길이 120자, K&R 중괄호, 한 줄 if에도 중괄호, 줄 끝 공백 금지, 파일 끝 개행, 와일드카드 import 금지.
- import 순서: static → `java.` → `javax.` → `org.` → `net.` → `com.` → 기타. 그룹 사이에 빈 줄을 넣는다(`com.moeun`은 `org.` 뒤 `com.` 그룹).
- Checkstyle이 못 잡는 것(직접 지킨다):
  - 클래스 이름은 명사, 메서드 이름은 동사로 시작한다.
  - 한국어 발음을 영어로 옮긴 이름을 쓰지 않는다(`moeunGeumaek` ✗).
  - 주석 기호(`//`) 뒤에 공백을 한 칸 둔다.
