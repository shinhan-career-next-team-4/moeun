export const AUTH_CHANNEL_NAME = "moeun-auth";

export type AuthChangeKind = "login" | "logout";

interface AuthSyncStore {
  clear(): void;
  loadPlan(): Promise<unknown>;
}

export interface AuthSync {
  notifyAuthChanged(kind: AuthChangeKind): void;
  dispose(): void;
}

const isKind = (value: unknown): value is AuthChangeKind => value === "login" || value === "logout";

const noop = () => {};

/**
 * 다른 탭의 로그인·로그아웃을 알려 받고, 이 탭의 공유 store를 비운 뒤 loadPlan()부터 다시 시작한다(D-078).
 * 채널 하나로 보내고 받는다. BroadcastChannel은 보낸 객체 자신에게는 돌려주지 않는다.
 */
export function createAuthSync({
  store,
  createChannel = (name) => new BroadcastChannel(name),
}: {
  store: AuthSyncStore;
  createChannel?: (name: string) => BroadcastChannel;
}): AuthSync {
  const channel = createChannel(AUTH_CHANNEL_NAME);
  let disposed = false;

  channel.onmessage = (event: MessageEvent) => {
    if (disposed || !isKind(event.data?.kind)) return;
    store.clear();
    // 실패는 이 호출자가 처리할 것이 없다(401은 래퍼가 이미 로그인으로 보냈다).
    new Promise((resolve) => resolve(store.loadPlan())).catch(noop);
  };

  return {
    notifyAuthChanged(kind) {
      if (disposed) return;
      channel.postMessage({ kind });
    },
    dispose() {
      disposed = true;
      channel.onmessage = null;
      channel.close();
    },
  };
}
