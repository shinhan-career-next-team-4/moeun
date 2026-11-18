/**
 * 보호 화면에서 로그인 화면으로 돌려보낸다. `router.replace`가 아니라 문서를 새로 연다.
 *
 * App Router는 떠난 화면을 지우지 않고 숨긴 채(`display: none`) 들고 있다가 돌아올 때 되살린다.
 * 그래서 라우터로 보내면 지난 세션의 로그인 폼이 입력값과 '제출 중' 상태 그대로 살아나
 * 로그인 버튼이 눌리지 않는다. 로그아웃·만료는 화면 상태를 모두 버려야 하는 자리다.
 * (`lib/api/client.ts`의 401 처리도 같은 이유로 전체 이동한다.)
 */
export function goToLogin(): void {
  window.location.replace("/login");
}
