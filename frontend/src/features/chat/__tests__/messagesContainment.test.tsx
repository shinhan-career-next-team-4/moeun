// @vitest-environment jsdom
// #398: 대화 목록 스크롤 영역은 위치 기준(relative)이라 안의 sr-only(absolute) 문구가 영역 밖으로 빠져 문서 높이를 늘리지 않는다.
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { ChatWindow } from "../index";
import { makeDeps, makeFakeApi, page } from "./helpers";

afterEach(cleanup);

describe("대화 목록 스크롤 영역", () => {
  it("relative라서 안의 absolute 요소가 스크롤 영역 안에 갇힌다", () => {
    const { deps } = makeDeps(makeFakeApi({ get: [page([], false)] }));
    render(<ChatWindow deps={deps} />);

    expect(screen.getByTestId("chat-messages").className).toContain("relative");
  });
});
