import re


def split_chunks(text: str, max_chars: int = 800, overlap: int = 100) -> list[str]:
    """문단 경계를 우선으로 max_chars 이하 청크로 나눈다. 긴 문단은 overlap을 두고 자른다."""
    paragraphs = [p.strip() for p in re.split(r"\n\s*\n", text) if p.strip()]
    chunks: list[str] = []
    buf = ""
    for p in paragraphs:
        p = re.sub(r"\s+", " ", p)
        if len(p) > max_chars:
            if buf:
                chunks.append(buf)
                buf = ""
            step = max_chars - overlap
            chunks.extend(p[i : i + max_chars] for i in range(0, len(p) - overlap, step))
            continue
        if len(buf) + len(p) + 1 > max_chars:
            chunks.append(buf)
            buf = p
        else:
            buf = f"{buf} {p}" if buf else p
    if buf:
        chunks.append(buf)
    return chunks
