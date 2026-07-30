# 작업 기록

`chat-server`(`/Users/juhyun/Desktop/chat-server`)를 참고 아키텍처로 삼아, `chat` 프로젝트를 처음부터 새로 구성하며 진행한 작업을 최신 날짜가 위로 오도록 정리한다.

## 진행 방식

- `chat-server`의 멀티모듈 구조(Kotlin/Spring Boot + Postgres + Redis pub/sub + WebSocket 세션 매니저 + nginx 로드밸런싱)를 뼈대로 가져가되, `chat-server`의 CLAUDE.md에 명시된 기존 문제(캐싱 미동작, 인증 취약, 정리 스레드 1회성 등)는 그대로 답습하지 않고 처음부터 짚고 넘어가는 방향으로 결정.
- 모듈별 실제 도메인/서비스/구현 코드는 사용자가 직접 작성하고, Claude는 Gradle 모듈 스캐폴딩·버전/빌드 설정·빌드 검증·이슈 진단을 담당하는 방식으로 역할을 나눠 진행 중.

---

## 2026-07-30 — `chat-persistence`의 `config`/`redis` 패키지 작성

- 사용자가 `chat-server`를 참고해 `config/RedisConfig.kt`, `config/CacheConfig.kt`, `redis/RedisMessageBroker.kt`를 직접 작성.
- 코드 설명 진행:
  - `RedisConfig.redisMessageListenerContainer`: Redis Pub/Sub **구독** 전용 컨테이너(`RedisTemplate`은 명령/응답형, 이건 구독형이라 별도 객체). `CachedThreadPool` 기반 taskExecutor(데몬 스레드)로 리스너 콜백을 실행하고, `setErrorHandler`로 콜백 예외를 처리. 이 시점엔 아직 채널이 등록되지 않은 빈 컨테이너.
  - `RedisMessageBroker.initialize()`(`@PostConstruct`): 생성자 주입이라 DI는 이미 끝난 뒤 호출됨. 다만 내부 로직(`Thread.sleep(30000)` 후 `cleanUpProcessedMessages()`)이 **1회성**이라 앱 구동 30초 뒤 딱 한 번만 청소되고 그 이후로는 반복 실행되지 않음 — `chat-server` CLAUDE.md에 명시됐던 바로 그 알려진 이슈가 그대로 이식됨.
  - `cleanUpProcessedMessages`: `processedMessages`(메시지 id → 처리 시각) 중복 처리 방지 캐시가 무한정 커지는 걸 막는 TTL(60초) 정리 로직. 위 1회성 버그 때문에 실질적으로는 `onMessage` 안의 size > 10000 트리밍만 안전망으로 작동.
  - `subscribeToRoom`/`unsubscribeFromRoom`: 로컬 `subscribeRooms` Set으로 인스턴스당 채널 리스너 등록을 멱등하게 관리(중복 `addMessageListener` 방지). `else` 분기 로그 메시지("Room ... does not exist")가 실제로는 "이미 구독/구독한 적 없음"을 뜻하는 오해의 소지 있는 문구임을 확인.
  - `broadcastToRoom`/`onMessage`: `DistributedMessage` 봉투로 감싸 Redis에 publish/구독. `excludeSeverId`로 발신 인스턴스 자기 자신은 무시, `processedMessages`로 중복 배달 방지. `localMessageHandler` 콜백 주입 방식으로 `WebSocketSessionManager`와의 순환 의존을 회피하는 설계임을 설명. 자기-이름 변수 섀도잉(`val message`), `ObjectMapper` 빈 모호성 가능성(`distributedObjectMapper` vs 기본 자동설정 빈), size-based 트리밍의 전체 정렬 비용 등 세부 이슈 확인.
- 커밋 직전 빌드 검증 중 재발 이슈 발견 및 수정: `chat-persistence/build.gradle.kts`가 이전에 고쳤던 `platform(...)` 방식에서 다시 `io.spring.dependency-management` + BOM `3.3.4` 수동 import로 되돌아가 있어, `chat-domain` 때와 같은 클래스의 문제(전역 BOM이 Kotlin 툴링 클래스패스를 오염)가 `NoClassDefFoundError: ClasspathEntrySnapshotter$Settings`로 재발 — `platform("...spring-boot-dependencies:4.1.0")` 방식으로 다시 교체해 해결.
- `./gradlew clean build` 전체 성공 확인 (`GenericJackson2JsonRedisSerializer(ObjectMapper)` 생성자 deprecated 경고 1건은 남아있음, 빌드 차단 아님).
- `46ca637` — chat-persistence config/redis 코드 추가 + BOM 재수정

## 2026-07-29 — `chat-persistence` 모듈 추가

- `chat-domain`과 동일한 원칙으로 모듈 뼈대만 추가:
  - `settings.gradle.kts`에 `chat-persistence` include.
  - `chat-persistence/build.gradle.kts`: `implementation(project(":chat-domain"))` + `spring-boot-starter-data-jpa`/`data-redis`/`cache`/`websocket`, Jackson(`jackson-module-kotlin`, `jackson-datatype-jsr310`), DB 드라이버(`h2`, `postgresql`) runtime. BOM은 `chat-domain`과 동일하게 `platform(...)` 방식으로 `4.1.0` 사용.
- 실제 JPA 리포지토리, `ChatServiceImpl`/`UserServiceImpl`, `RedisConfig`/`CacheConfig`, `RedisMessageBroker`, `WebSocketSessionManager` 등 구현 코드는 작성하지 않음 (사용자가 직접 작성 예정).
- 전체 멀티모듈 빌드 성공 확인.

## 2026-07-29 — `chat-domain` 모듈 추가

- 1차로 모듈 뼈대(`build.gradle.kts`, `settings.gradle.kts` include)만 추가, 실제 도메인 코드(엔티티/DTO/서비스 인터페이스)는 작성하지 않고 사용자 몫으로 남김.
- 사용자가 `WebSocketDto.kt`를 직접 작성하며 `sealed class`와 `abstract override` 필드의 역할에 대해 질문 → 설명:
  - `sealed class`로 하위 타입을 닫힌 집합으로 제한해 `when` exhaustiveness를 보장하고, `@JsonTypeInfo`/`@JsonSubTypes`와 짝을 이뤄 다형적 JSON 역직렬화를 안전하게 처리하는 패턴임을 설명.
  - `abstract val chatRoomId`/`timestamp`는 서브타입 공통 계약 역할이며, `ErrorMessage.chatRoomId`가 `Long?`으로 override narrowing되는 등의 특징을 설명.
  - `ChatMessage.type` 필드명이 `@JsonTypeInfo(property = "type")`의 클래스 판별자 키와 JSON에서 겹치는 잠재적 충돌 포인트를 발견(검증은 범위 밖으로 보류, `chat-server` 원본에도 동일하게 존재).
- 설명 도중, 사용자가 이미 `chat-server`의 `model`(`User`, `ChatRoom`, `ChatRoomMember`, `Message` + enum들)과 `service`(`ChatService`, `UserService`) 패키지를 직접 작성(참고 프로젝트 그대로 복사)해둔 것을 확인.
- Plan 모드로 전환해 `chat-domain` 빌드 실패 원인 진단 및 수정:
  - 원인: `chat-domain`의 수동 `dependencyManagement { imports { mavenBom(...) } }`가 프로젝트의 모든 컨피규레이션에 전역으로 버전 제약을 걸면서, Kotlin 컴파일러 플러그인 클래스패스(`kotlinCompilerPluginClasspathMain`)까지 오염시켜 `kotlin-stdlib`를 `2.4.0` → `2.3.21`로 부분 다운그레이드시키고, 그 결과 Kotlin Build Tools API에서 `getPluginClasspaths() is null` NPE로 컴파일 자체가 막힘. (`chat-application`은 같은 BOM을 써도 `org.springframework.boot` 플러그인이 스코프를 다르게 처리해 문제 없었음.)
  - 해결: `io.spring.dependency-management` 플러그인 제거, Gradle 네이티브 `implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))`로 교체해 루트와 버전 일치 + 오염 방지.
  - 조사 과정에서 만든 임시 테스트 파일 정리.
  - `./gradlew clean build` 전체 성공 확인 (경고 없음).
- `083ae7f` — chat-domain 모듈 추가 (model, dto, service) + 빌드 스크립트 수정

## 2026-07-27 — `chat-application` 모듈 추가

- 루트 `build.gradle.kts`를 단일 모듈 → 멀티모듈 컨벤션으로 전환 (`plugins { ... apply false }` + `subprojects {}` 블록).
- `chat-application` 모듈 생성: `@SpringBootApplication` + `main()`만 있는 최소 실행 진입점.
- 사용자가 직접 패키지명(`com.jh.chat.application` → `com.chat.application`), group/version(`com.chat` / `1.0.0`)을 수정하고, JPA 관련 의존성 및 `@EnableJpaAuditing`/`@EntityScan` 등 어노테이션을 추가.
- 임베디드 H2로 정상 기동되는 것까지 빌드/부팅 검증.
- 사용자 요청에 따라 루트 `build.gradle.kts` 최신화 및 정리:
  - Kotlin `2.4.0` / Spring Boot `4.1.0` / `io.spring.dependency-management` `1.1.7`로 버전 상향.
  - deprecated `val x by configurations` delegate 문법 → `add("implementation", ...)` 형태로 교체 (Gradle 10 호환).
  - `subprojects{}` 안에서 `apply(plugin = "...")`을 `plugins {}` 블록으로 바꿔봤으나, Gradle이 "동적 컨텍스트에서 `plugins{}` 사용 금지"라며 명시적으로 막아 원래 형태로 원복. IntelliJ의 "apply 문법이 오래됐다"는 경고는 이 컨텍스트에서는 false positive로 판단하고 그대로 유지하기로 함.
  - `chat-application`의 중복 `dependencyManagement { imports { mavenBom(...) } }` 제거 (Spring Boot 플러그인이 자동 적용).
- Spring Boot 4.1 업그레이드로 인한 실제 breaking change 발견 및 수정: `EntityScan`이 `org.springframework.boot.autoconfigure.domain` → `org.springframework.boot.persistence.autoconfigure` 패키지로 이동(Boot 4의 autoconfigure 모듈 세분화).
- `fa6a367` — chat-application 모듈 추가 + 루트 빌드 스크립트 최신화

## 2026-07-26 — 프로젝트 초기 설정

- `chat-server` 기존 프로젝트 구조 파악 (모듈 구성, 의존 방향, 인스턴스 간 실시간 메시지 흐름, 캐싱/인증의 알려진 이슈 등).
- 신규 프로젝트(`chat`)의 진행 방향 확정: 동일 아키텍처를 개선하며 재구현.
- GitHub 원격 저장소(`github.com/JuHyun419/chat-server`, 빈 저장소) 연결 확인 후 초기 커밋 및 푸시.
  - `4e10ae2` — Kotlin/Gradle 프로젝트 뼈대 (`build.gradle.kts`, `settings.gradle.kts`, gradle wrapper 등, 단일 모듈 상태)

---

## 현재 모듈 구성 요약

| 모듈 | 상태 | 비고 |
|---|---|---|
| `chat-application` | 코드 작성 완료 | 유일한 `@SpringBootApplication`, 실행 진입점 |
| `chat-domain` | 코드 작성 완료 | 엔티티(User/ChatRoom/ChatRoomMember/Message), DTO, 서비스 인터페이스 |
| `chat-persistence` | 작성 중 | `config`(RedisConfig/CacheConfig), `redis`(RedisMessageBroker) 완료. JPA 리포지토리·`ChatServiceImpl`/`UserServiceImpl`·`WebSocketSessionManager`는 아직 |
| `chat-websocket` | 미생성 | |
| `chat-api` | 미생성 | |
