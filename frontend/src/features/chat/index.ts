import type { PlanStore } from "../../api/planStore";

export interface ChatDeps {
  api: { request<T = unknown>(method: "GET" | "POST", path: string, body?: unknown): Promise<T> };
  polling: { register(stop: () => void): () => void };
  planStore: Pick<PlanStore, "getState" | "subscribe">;
}

export { ChatWindow } from "./ChatWindow";
export { ComparisonCard } from "./ComparisonCard";
export { useChatPolling } from "./useChatPolling";
export type { ChatMessage, ComparisonView } from "./types";
