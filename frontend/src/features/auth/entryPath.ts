/** 로그인 직후·첫 진입 화면(FR-PLAN-06): 계획이 있으면 결과, 없으면 빈 입력 화면 */
export function entryPath(hasPlan: boolean): string {
  return hasPlan ? "/result?welcome=1" : "/input";
}
