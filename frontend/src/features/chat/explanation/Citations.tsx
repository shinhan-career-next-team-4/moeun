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

