// @vitest-environment jsdom
// Issue #54 AC-2: 설명 메시지의 출처 링크(FR-AI-03, D-028, C12b-3, 결정 U-3, 결정 3 T-1·T-2).
// 출처 영역은 말풍선(data-testid="chat-message") 안의 data-testid="chat-citations", 항목은 data-testid="chat-citation"이다.
import { cleanup, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { copy, userMsg } from "../../__tests__/helpers";
import { bodyTextWithoutCitations, bubble, chatPage, explanationDone, explanationFailed, makeStore, mountChat } from "./helpers";

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

// 서로 다른 운용사 3곳. 응답 배열 순서가 순위다(C12b-3).
const CITATIONS = [
  {
    title: "KODEX 미국S&P500 투자설명서",
    sourceUrl: "https://www.samsungfund.com/upload/invest/2ETFE4-A.pdf",
    orgName: "삼성자산운용",
  },
  {
    title: "TIGER 미국S&P500 투자설명서",
    sourceUrl: "https://investments.miraeasset.com/tigeretf/upload/etf/20261008090949000003.pdf",
    orgName: "미래에셋자산운용",
  },
  {
    title: "ACE 미국S&P500 투자설명서",
    sourceUrl: "https://www.aceetf.co.kr/fund/K55101DH2364/prospectus.pdf",
    orgName: "한국투자신탁운용",
  },
];

const CONTENT =
  "지금 계획대로 이어 가면 평균일 때 3년 뒤 약 1,712만원이 돼요. 목표인 약 2,000만원보다 약 288만원 부족해요. S&P500 추종 ETF의 과거 흐름을 바탕으로 한 가정이며, 미래 수익을 보장하지 않아요.";

async function mountWith(messages: Parameters<typeof chatPage>[0]) {
  const { store } = makeStore(); // view가 없어 F-3은 나가지 않는다. 복원(F-2)으로 받은 메시지만 그린다.
  return mountChat(store, { get: [chatPage(messages, false)] });
}

const items = (el: HTMLElement) => within(el).queryAllByTestId("chat-citation");
const linkOf = (item: HTMLElement) => within(item).getByRole("link") as HTMLAnchorElement;

describe("AC-2 출처 링크 (FR-AI-03, D-028, C12b-3, 결정 U-3·T-1·T-2)", () => {
  it("설명 메시지의 출처 3개를 응답 배열 순서대로 모두 링크로 그린다: 글자는 문서 제목(상품명 포함) 그대로, 주소는 sourceUrl, 새 탭", async () => {
    await mountWith([explanationDone(3001, CONTENT, CITATIONS)]);
    const message = bubble(3001)!;
    expect(message).toBeTruthy();
    expect(within(message).getAllByRole("link")).toHaveLength(3);

    const list = items(message);
    expect(list).toHaveLength(3);
    const links = list.map(linkOf);
    expect(links.map((a) => a.textContent)).toEqual(CITATIONS.map((c) => c.title));
    expect(links.map((a) => a.getAttribute("href"))).toEqual(CITATIONS.map((c) => c.sourceUrl));
    for (const a of links) {
      expect(a.getAttribute("target")).toBe("_blank");
      const rel = (a.getAttribute("rel") ?? "").split(/\s+/);
      expect(rel).toContain("noopener");
      expect(rel).toContain("noreferrer");
    }
  });

  it("링크 옆(링크 밖)에 기관명을 보여 준다: 서로 다른 기관 3곳이 각 출처 항목에 순서대로 보인다", async () => {
    await mountWith([explanationDone(3002, CONTENT, CITATIONS)]);
    expect(new Set(CITATIONS.map((c) => c.orgName)).size).toBe(3);
    const list = items(bubble(3002)!);
    expect(list).toHaveLength(3);
    list.forEach((item, i) => {
      expect(item.textContent).toContain(CITATIONS[i].orgName);
      expect(linkOf(item).textContent).not.toContain(CITATIONS[i].orgName);
    });
  });

  it("출처가 하나인 완료 설명 메시지에도 그 출처를 새 탭 링크 하나로 그리고 링크 밖에 기관명을 보여 준다 (검토 plan-8)", async () => {
    await mountWith([explanationDone(3004, CONTENT, [CITATIONS[1]])]);
    const message = bubble(3004)!;
    expect(message).toBeTruthy();
    expect(message.getAttribute("data-message-type")).toBe("EXPLANATION");
    expect(within(message).getAllByRole("link")).toHaveLength(1);
    const list = items(message);
    expect(list).toHaveLength(1);
    const link = linkOf(list[0]);
    expect(link.textContent).toBe(CITATIONS[1].title);
    expect(link.getAttribute("href")).toBe(CITATIONS[1].sourceUrl);
    expect(link.getAttribute("target")).toBe("_blank");
    const rel = (link.getAttribute("rel") ?? "").split(/\s+/);
    expect(rel).toContain("noopener");
    expect(rel).toContain("noreferrer");
    expect(list[0].textContent).toContain(CITATIONS[1].orgName);
    expect(link.textContent).not.toContain(CITATIONS[1].orgName);
    expect(bodyTextWithoutCitations(message)).toBe(CONTENT);
  });

  it("출처 영역을 뺀 말풍선 본문은 서버 content와 정확히 같다(프론트가 본문에 문구를 덧붙이지 않음)", async () => {
    await mountWith([explanationDone(3003, CONTENT, CITATIONS)]);
    const message = bubble(3003)!;
    expect(within(message).getByTestId("chat-citations")).toBeTruthy();
    expect(bodyTextWithoutCitations(message)).toBe(CONTENT);
  });

  it("citations가 있는 다른 완료 메시지(ANSWER·RECALC_RESULT·CLARIFY·OUT_OF_SCOPE)에도 링크를 그리고, 출처가 없는 메시지·질문·실패 메시지에는 출처 영역이 없다", async () => {
    // 유형마다 출처 수·순서·제목·주소·링크 밖 기관명·새 탭(target·rel)과, 출처 영역을 뺀 본문 = 서버 content를 모두 본다(설명 메시지와 같은 기준).
    const cases = [
      { id: 3011, type: "ANSWER", content: "질문에 대한 답변입니다. 서버 문장 그대로예요", citations: [CITATIONS[1], CITATIONS[2]] },
      { id: 3012, type: "RECALC_RESULT", content: "월 50만원이면 평균일 때 약 2,050만원이 돼요", citations: [CITATIONS[2], CITATIONS[0]] },
      { id: 3015, type: "CLARIFY", content: "바꿀 값을 알려 주세요", citations: [CITATIONS[0], CITATIONS[2], CITATIONS[1]] },
      { id: 3016, type: "OUT_OF_SCOPE", content: "그 질문은 도와드리기 어려워요", citations: [CITATIONS[1]] },
    ];
    await mountWith([
      userMsg(3010),
      ...cases.slice(0, 2).map((c) => explanationDone(c.id, c.content, c.citations, c.type)),
      explanationDone(3013, "출처 없는 설명", []),
      explanationFailed(3014, copy["chat.failed"]),
      ...cases.slice(2).map((c) => explanationDone(c.id, c.content, c.citations, c.type)),
    ]);
    for (const c of cases) {
      const label = `${c.type} ${c.id}`;
      const message = bubble(c.id)!;
      expect(message, label).toBeTruthy();
      expect(message.getAttribute("data-message-type"), label).toBe(c.type);
      expect(within(message).getAllByRole("link"), label).toHaveLength(c.citations.length);
      const list = items(message);
      expect(list, label).toHaveLength(c.citations.length);
      const links = list.map(linkOf);
      expect(links.map((a) => a.textContent), label).toEqual(c.citations.map((x) => x.title));
      expect(links.map((a) => a.getAttribute("href")), label).toEqual(c.citations.map((x) => x.sourceUrl));
      list.forEach((item, i) => {
        expect(item.textContent, label).toContain(c.citations[i].orgName);
        expect(linkOf(item).textContent, label).not.toContain(c.citations[i].orgName);
      });
      for (const a of links) {
        expect(a.getAttribute("target"), label).toBe("_blank");
        const rel = (a.getAttribute("rel") ?? "").split(/\s+/);
        expect(rel, label).toContain("noopener");
        expect(rel, label).toContain("noreferrer");
      }
      expect(bodyTextWithoutCitations(message), label).toBe(c.content);
    }
    for (const id of [3010, 3013, 3014]) {
      const message = bubble(id)!;
      expect(message, String(id)).toBeTruthy();
      expect(within(message).queryByTestId("chat-citations"), String(id)).toBeNull();
      expect(within(message).queryAllByRole("link"), String(id)).toHaveLength(0);
    }
  });
});
