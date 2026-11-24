// @vitest-environment jsdom
// Issue #375: 대화 답의 출처는 운용사 로고 칩 하나로 접혀 있고, 누르면 문서 제목 링크 목록이 펼쳐진다(D-028, FR-AI-03).
import { act, cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { copy } from "../../__tests__/helpers";
import { Citations } from "../Citations";

afterEach(cleanup);

const SAMSUNG = { title: "KODEX 미국S&P500 투자설명서", sourceUrl: "https://example.com/kodex.pdf", orgName: "삼성자산운용" };
const MIRAE = { title: "TIGER 미국S&P500 투자설명서", sourceUrl: "https://example.com/tiger.pdf", orgName: "미래에셋자산운용" };
const SAMSUNG_200 = { title: "KODEX 200 투자설명서", sourceUrl: "https://example.com/kodex200.pdf", orgName: "삼성자산운용" };
const UNKNOWN = { title: "예시 자료", sourceUrl: "https://example.com/x.pdf", orgName: "예시운용" };

const chip = () => within(screen.getByTestId("chat-citations")).getByRole("button");
const logos = () => screen.queryAllByTestId("citation-org-logo");

describe("#375 출처 칩", () => {
  it("기본은 접혀 있다: 첫 기관 이름과 '+나머지 문서 수'만 보이고 링크는 없다", () => {
    render(<Citations citations={[SAMSUNG, MIRAE]} />);

    expect(chip().getAttribute("aria-expanded")).toBe("false");
    expect(chip().textContent).toContain("삼성자산운용");
    expect(chip().textContent).toContain("+1");
    expect(chip().textContent).not.toContain("미래에셋자산운용");
    expect(screen.queryAllByRole("link")).toHaveLength(0);
    expect(screen.queryAllByTestId("chat-citation")).toHaveLength(0);
  });

  it("칩 이름은 스크린리더에 출처 건수와 운용사를 모두 알린다(로고만 보이는 둘째 기관 포함)", () => {
    render(<Citations citations={[SAMSUNG, MIRAE]} />);

    const name = copy["chat.citationsChipLabel"].replace("{n}", "2").replace("{orgs}", "삼성자산운용, 미래에셋자산운용");
    expect(screen.getByRole("button", { name })).toBe(chip());
  });

  it("서로 다른 운용사가 셋 이상이어도 로고는 둘까지만 겹쳐 보인다", () => {
    render(<Citations citations={[SAMSUNG, MIRAE, UNKNOWN]} />);

    expect(logos()).toHaveLength(2);
    expect(chip().textContent).toContain("+2");
  });

  it("(예외) 출처가 없으면 아무것도 그리지 않는다", () => {
    const { container } = render(<Citations citations={[]} />);

    expect(container.innerHTML).toBe("");
  });

  it("서로 다른 운용사 로고를 겹쳐 보인다(정적 파일, 외부 주소 아님)", () => {
    render(<Citations citations={[SAMSUNG, MIRAE]} />);

    const images = logos().map((el) => el.querySelector("img"));
    expect(images).toHaveLength(2);
    for (const img of images) {
      expect(img).toBeTruthy();
      expect(img!.getAttribute("src")).not.toMatch(/^https?:/);
      expect(img!.getAttribute("alt")).toBe("");
    }
  });

  it("같은 운용사 문서가 둘이면 로고는 하나, 개수는 문서 수로 센다", () => {
    render(<Citations citations={[SAMSUNG, SAMSUNG_200]} />);

    expect(logos()).toHaveLength(1);
    expect(chip().textContent).toContain("+1");
  });

  it("누르면 문서 제목 링크(새 탭)와 기관명이 펼쳐지고, 다시 누르면 접힌다", () => {
    render(<Citations citations={[SAMSUNG, MIRAE]} />);

    act(() => fireEvent.click(chip()));
    expect(chip().getAttribute("aria-expanded")).toBe("true");
    const items = screen.getAllByTestId("chat-citation");
    expect(items).toHaveLength(2);
    const links = screen.getAllByRole("link");
    expect(links.map((a) => a.textContent)).toEqual([SAMSUNG.title, MIRAE.title]);
    expect(links.map((a) => a.getAttribute("href"))).toEqual([SAMSUNG.sourceUrl, MIRAE.sourceUrl]);
    items.forEach((item, i) => {
      expect(item.textContent).toContain([SAMSUNG, MIRAE][i].orgName);
      expect(within(item).getByRole("link").textContent).not.toContain([SAMSUNG, MIRAE][i].orgName);
    });
    for (const a of links) {
      expect(a.getAttribute("target")).toBe("_blank");
      expect(a.getAttribute("rel")).toBe("noopener noreferrer");
    }
    const panel = document.getElementById(chip().getAttribute("aria-controls")!);
    expect(panel?.contains(items[0])).toBe(true);

    act(() => fireEvent.click(chip()));
    expect(chip().getAttribute("aria-expanded")).toBe("false");
    expect(screen.queryAllByRole("link")).toHaveLength(0);
  });

  it("칩은 키보드로 여닫을 수 있는 진짜 버튼이다(type=button, 포커스 가능)", () => {
    render(<Citations citations={[SAMSUNG]} />);

    const button = chip() as HTMLButtonElement;
    expect(button.tagName).toBe("BUTTON");
    expect(button.type).toBe("button");
    button.focus();
    expect(document.activeElement).toBe(button);
  });

  it("(예외) 로고 파일이 없는 기관은 이름 첫 글자 배지로 대신한다", () => {
    render(<Citations citations={[UNKNOWN]} />);

    const [badge] = logos();
    expect(badge.querySelector("img")).toBeNull();
    expect(badge.textContent).toBe("예");
  });

  it("(예외) 출처가 1건이면 '+0'을 붙이지 않는다", () => {
    render(<Citations citations={[MIRAE]} />);

    expect(chip().textContent).toContain("미래에셋자산운용");
    expect(chip().textContent).not.toMatch(/\+\d/);
  });
});
