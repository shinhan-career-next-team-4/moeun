import numpy as np
from openai import AsyncOpenAI

EMBEDDING_DIM = 1536   # schema.sql chunk_embedding.embedding vector(1536)


class Embedder:
    def __init__(self, client: AsyncOpenAI | None, model: str):
        self._client = client   # None = OPENAI_API_KEY 미설정. 서버는 뜨고 검색만 실패한다
        self.model = model

    async def embed(self, texts: list[str]) -> list[np.ndarray]:
        if self._client is None:
            raise RuntimeError("OPENAI_API_KEY is not set")
        resp = await self._client.embeddings.create(model=self.model, input=texts)
        vectors = [np.asarray(d.embedding, dtype=np.float32) for d in sorted(resp.data, key=lambda d: d.index)]
        if any(v.shape != (EMBEDDING_DIM,) for v in vectors):
            raise ValueError(f"{self.model} must return {EMBEDDING_DIM}-dim vectors")
        return vectors
