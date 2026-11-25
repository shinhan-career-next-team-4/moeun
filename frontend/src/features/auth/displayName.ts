// 로그인한 아이디를 헤더에 "{아이디} 님"으로 보여 주려고 보관한다.
// 서버는 로그인 응답으로 이름을 주지 않고(P-1 본문은 {}), /api/auth/me도 없다.
// 탭을 닫으면 사라지면 되므로 sessionStorage를 쓰고, 막혀 있어도 화면은 그대로 그려진다.
const KEY = "moeun.displayName";

export function rememberDisplayName(loginId: string): void {
  try {
    sessionStorage.setItem(KEY, loginId);
  } catch {
    // 사생활 보호 모드 등에서 막힐 수 있다. 이름만 안 보일 뿐이다.
  }
}

export function readDisplayName(): string | null {
  try {
    return sessionStorage.getItem(KEY);
  } catch {
    return null;
  }
}

export function forgetDisplayName(): void {
  try {
    sessionStorage.removeItem(KEY);
  } catch {
    // 위와 같다.
  }
}
