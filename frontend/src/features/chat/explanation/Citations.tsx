import { useId, useState } from "react";
import { COPY } from "../copy";
import type { ChatCitation } from "../types";

/** 완료 메시지의 출처 목록. 링크 글자는 문서 제목 그대로, 기관명은 링크 밖에 둔다(D-028, PD-011). */
export function Citations({ citations }: { citations: ChatCitation[] }) {
  return (
    <ul data-testid="chat-citations">
      {citations.map((citation, index) => (
        <li key={`${index}-${citation.sourceUrl}`} data-testid="chat-citation">
          <a href={citation.sourceUrl} target="_blank" rel="noopener noreferrer">
            {citation.title}
          </a>{" "}
          <span>{citation.orgName}</span>
        </li>
      ))}
    </ul>
  );
}

/** 결과 설명의 ETF 위험 설명(#283). 대화 공간을 덜 차지하도록 기본은 접고, 제목을 눌러 출처를 펼친다. */
export function EtfRiskCitations({ citations }: { citations: ChatCitation[] }) {
  const [open, setOpen] = useState(false);
  const panelId = useId();
  return (
    <div data-testid="chat-etf-risk" className="mt-2">
      <button
        type="button"
        aria-expanded={open}
        aria-controls={panelId}
        onClick={() => setOpen((value) => !value)}
        className="text-xs font-semibold text-text-muted hover:text-text"
      >
        {COPY.etfRiskTitle} {open ? "▾" : "▸"}
      </button>
      <div id={panelId} hidden={!open}>
        {open && <Citations citations={citations} />}
      </div>
    </div>
  );
}
