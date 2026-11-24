import { useId, useState } from "react";
import { COPY } from "../copy";
import type { ChatCitation } from "../types";
import { orgLogo } from "./orgLogos";

/** 칩에 겹쳐 보이는 로고 수. FR-AI-03(서로 다른 운용사 2곳 이상)이 접힌 상태에서도 보이게 한다. */
const MAX_LOGOS = 2;

/**
 * 완료 메시지의 출처(#375). 기본은 운용사 로고 + 첫 기관 이름 + 나머지 문서 수의 칩 하나로 접고,
 * 누르면 문서 제목 링크 목록을 펼친다. 링크 글자는 문서 제목 그대로, 기관명은 링크 밖에 둔다(D-028, PD-011).
 */
export function Citations({ citations }: { citations: ChatCitation[] }) {
  const [open, setOpen] = useState(false);
  const panelId = useId();
  const orgs = [...new Set(citations.map((citation) => citation.orgName))];
  const rest = citations.length - 1;

  return (
    <div data-testid="chat-citations" className="mt-2">
      <button
        type="button"
        aria-expanded={open}
        aria-controls={panelId}
        onClick={() => setOpen((value) => !value)}
        className="inline-flex items-center gap-1.5 rounded-full border border-border bg-surface py-1 pl-1 pr-2.5 text-xs text-text hover:bg-surface-muted"
      >
        <span className="sr-only">{COPY.citationsLabel} </span>
        <span className="flex -space-x-1.5">
          {orgs.slice(0, MAX_LOGOS).map((org) => (
            <OrgLogo key={org} orgName={org} />
          ))}
        </span>
        <span>{citations[0].orgName}</span>
        {rest > 0 && <span className="text-text-muted">{COPY.citationsMore.replace("{n}", String(rest))}</span>}
      </button>
      <ul id={panelId} hidden={!open} className="mt-1.5 flex flex-col gap-1 text-xs">
        {open &&
          citations.map((citation, index) => (
            <li key={`${index}-${citation.sourceUrl}`} data-testid="chat-citation">
              <a href={citation.sourceUrl} target="_blank" rel="noopener noreferrer" className="underline">
                {citation.title}
              </a>{" "}
              <span className="text-text-muted">{citation.orgName}</span>
            </li>
          ))}
      </ul>
    </div>
  );
}

/** 기관 로고. 로고 파일이 없으면 이름 첫 글자 배지. 기관 이름은 칩 글자가 읽으므로 로고는 장식이다. */
function OrgLogo({ orgName }: { orgName: string }) {
  const src = orgLogo(orgName);
  return (
    <span
      data-testid="citation-org-logo"
      aria-hidden="true"
      className="flex size-5 items-center justify-center overflow-hidden rounded-full border border-surface bg-surface-muted text-[10px] font-semibold text-text-muted"
    >
      {src ? <img src={src} alt="" className="size-full object-cover" /> : orgName.charAt(0)}
    </span>
  );
}
