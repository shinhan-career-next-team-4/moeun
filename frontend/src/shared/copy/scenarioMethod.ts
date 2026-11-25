import { COPY } from "./copy";

/**
 * 3구간을 어떻게 나눴는지 가리키는 한 줄(#156). 계산에 쓴 ETF 종목 이름을 산정 방식 앞에 붙인다.
 *
 * 종목 이름은 문구에 박지 않고 서버 display(`assumptions.etfProductName`)에서 받는다. 종목이 없으면
 * (ETF 0%) 산정 방식만, 산정 방식 코드를 모르면 줄 자체를 두지 않는다(undefined).
 */
export function scenarioMethodLine(
  scenarioMethod: string | null,
  productName: string | undefined,
): string | undefined {
  const method = scenarioMethod
    ? COPY[`result.scenarioMethodShort.${scenarioMethod}` as keyof typeof COPY]
    : undefined;
  if (!method) return undefined;
  if (!productName) return method;
  return `${COPY["result.scenarioMethodProduct"].replace("{product}", productName)} · ${method}`;
}
