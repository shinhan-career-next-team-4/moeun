# Code Quality

> 참조: [frontend-fundamentals.com](https://www.frontend-fundamentals.com) 핵심 메시지: **"좋은 코드 = 변경하기 쉬운 코드"**

프론트엔드 코드의 품질을 판단하는 4가지 기준: 가독성, 예측 가능성, 응집도, 결합도. 이 원칙들은 상충할 수 있으며, 상황에 따라 우선순위를 판단해야 한다.

> **⚡ 원칙 간 충돌 시 판단 기준:**
>
> 1. 버그 위험이 높은 경우 → 응집도/결합도 우선
> 2. 버그 위험이 낮은 경우 → 가독성 우선
> 3. 팀 규모가 클수록 → 예측 가능성이 중요해짐
> 4. 코드가 자주 변경되는 영역 → 결합도를 낮게 유지
>
> "모든 코드에 4원칙을 100% 적용할 수는 없다. 중요한 것은 trade-off를 인식하고, 의도적으로 선택하는 것이다."

---

## 1. 가독성 (Readability)

코드를 읽는 사람이 **한 번에 머리에 올려야 하는 맥락(context)을 최소화**하는 것이 핵심이다.

### 1.1 맥락 줄이기

한 함수/컴포넌트 안에 여러 관심사가 섞이면 읽는 사람의 인지 부하가 증가한다. 각 분기/케이스를 별도 함수로 분리하면 한 번에 하나의 맥락만 이해하면 된다.

```tsx
// ❌ 맥락이 많은 코드
function UserProfile({ user }: { user: User }) {
    if (user.type === 'admin') {
        // 20줄의 admin 렌더링 로직
    } else if (user.type === 'member') {
        // 20줄의 member 렌더링 로직
    } else {
        // 20줄의 guest 렌더링 로직
    }
}

// ✅ 맥락을 분리한 코드
function UserProfile({ user }: { user: User }) {
    switch (user.type) {
        case 'admin':
            return <AdminProfile user={user} />
        case 'member':
            return <MemberProfile user={user} />
        default:
            return <GuestProfile />
    }
}
```

### 1.2 구현 상세 추상화

"어떻게(how)"보다 **"무엇을(what)"** 하는지가 먼저 보여야 한다. 복잡한 로직은 의미 있는 이름의 함수로 추출한다.

```tsx
// ❌ 구현 상세가 노출됨
function checkout() {
    const items = cart.filter((item) => item.selected && item.stock > 0)
    const total = items.reduce(
        (sum, item) => sum + item.price * item.quantity,
        0,
    )
    const discount = total > 50000 ? total * 0.1 : 0
    // ... 30줄의 결제 로직
}

// ✅ 추상화된 코드
function checkout() {
    const items = getAvailableItems(cart)
    const total = calculateTotal(items)
    const discount = calculateDiscount(total)
    return processPayment({ items, total, discount })
}
```

### 1.3 이름 붙이기

변수, 함수, 컴포넌트의 이름은 **역할과 의도를 명확히** 전달해야 한다. 축약어를 피하고, boolean은 `is/has/should` 접두사를 사용한다.

```tsx
// ❌
const d = new Date()
const flag = user.age >= 19
const list = items.filter((i) => i.a)

// ✅
const currentDate = new Date()
const isAdult = user.age >= 19
const activeItems = items.filter((item) => item.isActive)
```

### 1.4 위에서 아래로 읽기

코드는 위에서 아래로 자연스럽게 읽혀야 한다. Early return으로 예외를 먼저 처리하고, 핵심 로직을 아래에 둔다. 과도한 중첩을 피한다.

```tsx
// ❌ 깊은 중첩
function processOrder(order: Order) {
    if (order) {
        if (order.items.length > 0) {
            if (order.paymentMethod) {
                // 실제 로직
            }
        }
    }
}

// ✅ Early return
function processOrder(order: Order) {
    if (!order) return
    if (order.items.length === 0) return
    if (!order.paymentMethod) return

    // 실제 로직 — 중첩 없이 깔끔
}
```

---

## 2. 예측 가능성 (Predictability)

함수/컴포넌트의 동작을 **이름과 시그니처만 보고 예측**할 수 있어야 한다.

### 2.1 이름과 동작의 일치

함수 이름이 약속하는 것 이상의 일을 해서는 안 된다.

```tsx
// ❌ 이름은 "get"인데 사이드 이펙트 발생
function getUser(id: string) {
    analytics.track('user_viewed', { id }) // 숨은 사이드 이펙트!
    return userStore.get(id)
}

// ✅ 사이드 이펙트를 분리
function getUser(id: string) {
    return userStore.get(id)
}

function trackUserView(id: string) {
    analytics.track('user_viewed', { id })
}
```

### 2.2 반환 타입 통일

같은 종류의 함수는 일관된 반환 타입을 가져야 한다. `null | undefined | false | []`를 혼용하지 않는다.

```tsx
// ❌ 반환 타입이 일관되지 않음
function findItem(id: string): Item | null | undefined {
    if (!id) return undefined
    const item = items.find((i) => i.id === id)
    return item ?? null
}

// ✅ 일관된 반환 타입
function findItem(id: string): Item | null {
    if (!id) return null
    return items.find((i) => i.id === id) ?? null
}
```

### 2.3 숨은 로직 노출

암묵적 동작을 명시적으로 만든다. 컴포넌트가 렌더링 외에 하는 일(API 호출, 상태 변경 등)이 있으면 이름이나 Props로 드러내야 한다.

```tsx
// ❌ 렌더링 시 숨은 사이드 이펙트
function UserCard({ userId }: { userId: string }) {
    useEffect(() => {
        markAsRead(userId) // 카드가 보이면 자동으로 읽음 처리?
    }, [userId])
    // ...
}

// ✅ 의도를 명시
function UserCard({
    userId,
    onView,
}: {
    userId: string
    onView?: (id: string) => void
}) {
    useEffect(() => {
        onView?.(userId)
    }, [userId, onView])
    // ...
}
```

### 2.4 일관된 패턴

유사한 기능은 같은 패턴으로 구현한다. 팀에서 한 번 패턴을 익히면, 나머지 코드의 동작도 예측할 수 있다.

```tsx
// 모든 페이지 컴포넌트가 같은 패턴을 따름
function SomePage() {
    return (
        <ErrorBoundary fallback={<ErrorFallback />}>
            <Suspense fallback={<PageSkeleton />}>
                <SomePageContent />
            </Suspense>
        </ErrorBoundary>
    )
}
```

---

## 3. 응집도 (Cohesion)

**함께 수정되는 코드는 함께 둔다.** 한 기능을 변경할 때 수정해야 하는 코드가 여러 곳에 흩어져 있으면 변경이 어렵고 실수가 발생한다.

### 3.1 함께 수정되는 코드 배치

```tsx
// ❌ 관련 코드가 흩어져 있음
// constants/status.ts
export const STATUS_LABELS = { active: '활성', inactive: '비활성' }

// types/status.ts
export type Status = 'active' | 'inactive'

// utils/status.ts
export function getStatusColor(status: Status) {
    /* ... */
}

// ✅ 함께 수정되는 코드를 한 모듈에
// models/status.ts
export type Status = 'active' | 'inactive'
export const STATUS_LABELS: Record<Status, string> = {
    active: '활성',
    inactive: '비활성',
}
export function getStatusColor(status: Status) {
    /* ... */
}
```

### 3.2 매직 넘버 제거

의미를 알 수 없는 숫자/문자열은 상수로 추출하고, 사용처 가까이에 둔다.

```tsx
// ❌
if (password.length < 8) {
    /* ... */
}
if (retryCount > 3) {
    /* ... */
}

// ✅
const MIN_PASSWORD_LENGTH = 8
const MAX_RETRY_COUNT = 3

if (password.length < MIN_PASSWORD_LENGTH) {
    /* ... */
}
if (retryCount > MAX_RETRY_COUNT) {
    /* ... */
}
```

### 3.3 폼 응집도

폼의 유효성 검사, 상태 관리, 제출 로직은 한 곳에서 관리한다.

```tsx
// ✅ 폼 관련 로직이 응집됨
function useSignupForm() {
    const [values, setValues] = useState({ email: '', password: '' })

    const errors = useMemo(
        () => ({
            email: !isValidEmail(values.email)
                ? '올바른 이메일을 입력하세요'
                : null,
            password:
                values.password.length < MIN_PASSWORD_LENGTH
                    ? `비밀번호는 ${MIN_PASSWORD_LENGTH}자 이상이어야 합니다`
                    : null,
        }),
        [values],
    )

    const isValid = Object.values(errors).every((e) => e === null)

    const handleSubmit = async () => {
        if (!isValid) return
        await signupAPI(values)
    }

    return { values, setValues, errors, isValid, handleSubmit }
}
```

### 3.4 응집도 vs 가독성 판단 기준

- 함께 수정하지 않으면 **버그가 날 위험**이 큰 경우 → 응집도 우선 (코드 모아두기)
- 위험이 크지 않은 경우 → 가독성 우선 (중복 허용)

---

## 4. 결합도 (Coupling)

모듈/컴포넌트 간 **의존성을 최소화**한다. 결합도가 높으면 한 곳의 변경이 여러 곳에 파급된다.

### 4.1 책임 분리

하나의 컴포넌트/함수에 여러 책임이 있으면 분리한다. 변경 이유(reason to change)가 2개 이상이면 분리의 신호다.

```tsx
// ❌ UI + 데이터 패칭 + 비즈니스 로직이 한 컴포넌트에
function OrderPage() {
    const { data } = useQuery({ queryKey: ['orders'], queryFn: fetchOrders })
    const total = data?.reduce((sum, order) => sum + order.amount, 0) ?? 0
    const filtered = data?.filter((order) => order.status === 'pending') ?? []

    return (
        <div>
            <h1>
                주문 목록 ({filtered.length}건, 총 {formatCurrency(total)})
            </h1>
            {filtered.map((order) => (
                <OrderItem key={order.id} order={order} />
            ))}
        </div>
    )
}

// ✅ 관심사 분리
function useOrders() {
    const { data: orders = [] } = useQuery({
        queryKey: ['orders'],
        queryFn: fetchOrders,
    })
    const pendingOrders = orders.filter((order) => order.status === 'pending')
    const total = orders.reduce((sum, order) => sum + order.amount, 0)
    return { orders, pendingOrders, total }
}

function OrderPage() {
    const { pendingOrders, total } = useOrders()
    return (
        <div>
            <h1>
                주문 목록 ({pendingOrders.length}건, 총 {formatCurrency(total)})
            </h1>
            {pendingOrders.map((order) => (
                <OrderItem key={order.id} order={order} />
            ))}
        </div>
    )
}
```

### 4.2 코드 중복 허용

잘못된 추상화보다 약간의 중복이 낫다. DRY(Don't Repeat Yourself)를 맹목적으로 따르지 않는다.

```tsx
// ❌ 과도한 추상화 — 변경 이유가 다른 것을 억지로 합침
function GenericCard({
    type,
    data,
}: {
    type: 'user' | 'product' | 'order'
    data: any
}) {
    // type에 따라 분기가 20개...
}

// ✅ 각각의 변경 이유가 다르면 별도 컴포넌트
function UserCard({ user }: { user: User }) {
    /* ... */
}
function ProductCard({ product }: { product: Product }) {
    /* ... */
}
function OrderCard({ order }: { order: Order }) {
    /* ... */
}
```

### 4.3 Props Drilling 제거

Props가 3단계 이상 내려가면 다음 패턴을 고려한다:

1. **합성(Composition)**: children을 활용하여 중간 컴포넌트가 props를 알 필요 없게 한다
2. **Context**: 여러 컴포넌트가 같은 데이터를 공유할 때
3. **커스텀 훅**: 데이터 접근 로직을 훅으로 추출

```tsx
// ❌ Props Drilling
function Page() {
    const user = useUser()
    return <Layout user={user} />
}
function Layout({ user }: { user: User }) {
    return <Sidebar user={user} />
}
function Sidebar({ user }: { user: User }) {
    return <UserAvatar name={user.name} />
}

// ✅ 합성 패턴
function Page() {
    const user = useUser()
    return (
        <Layout sidebar={<UserAvatar name={user.name} />}>
            <MainContent />
        </Layout>
    )
}
```

### 4.4 외부 라이브러리 래핑

외부 라이브러리 의존은 래퍼를 통해 간접적으로 사용한다. 라이브러리 교체 시 수정 범위를 최소화한다.

## 원칙 간 충돌 해결

4대 원칙은 서로 상충할 수 있다. 판단 기준:

1. **버그 위험이 높은 경우** → 응집도/결합도 우선
2. **버그 위험이 낮은 경우** → 가독성 우선
3. **팀 규모가 클수록** → 예측 가능성이 중요해짐
4. **코드가 자주 변경되는 영역** → 결합도를 낮게 유지

> "모든 코드에 4원칙을 100% 적용할 수는 없다. 중요한 것은 trade-off를 인식하고, 의도적으로 선택하는 것이다."
