import type { ComparisonPlanStore } from "./comparison";

export interface ChatDeps {
  api: { request<T = unknown>(method: "GET" | "POST", path: string, body?: unknown): Promise<T> };
  polling: { register(stop: () => void): () => void };
  planStore: ComparisonPlanStore;
}

export { ChatWindow } from "./ChatWindow";
export { useChatPolling } from "./useChatPolling";
export type { ChatMessage } from "./types";
