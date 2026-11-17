import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "목돈 플래너",
  description: "목돈 마련 목표를 세우고 계획을 비교해요.",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="ko" className="h-full antialiased">
      <body className="flex min-h-full flex-col">{children}</body>
    </html>
  );
}
