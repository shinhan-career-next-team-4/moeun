from pgvector.psycopg import register_vector_async
from psycopg import AsyncConnection
from psycopg_pool import AsyncConnectionPool


async def _configure(conn: AsyncConnection) -> None:
    await register_vector_async(conn)


def create_pool(database_url: str) -> AsyncConnectionPool:
    # lifespan에서 open()/close() 한다
    return AsyncConnectionPool(database_url, min_size=1, max_size=5, configure=_configure, open=False)
