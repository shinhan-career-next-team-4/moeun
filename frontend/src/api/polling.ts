export interface PollingRegistry {
  /** 폴링 루프가 시작할 때 중단 함수를 등록한다. 돌려받은 함수로 해제한다. */
  register(stop: () => void): () => void;
  /** 등록된 중단 함수를 한 번씩 부르고 비운다. */
  stopAll(): void;
}

export function createPollingRegistry(): PollingRegistry {
  const stops = new Set<() => void>();
  return {
    register(stop) {
      stops.add(stop);
      return () => {
        stops.delete(stop);
      };
    },
    stopAll() {
      const pending = [...stops];
      stops.clear();
      pending.forEach((stop) => stop());
    },
  };
}

export const polling = createPollingRegistry();
