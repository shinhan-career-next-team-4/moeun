import { AppHeader, AuthGate } from "@/features/auth";

export default function MemberLayout({ children }: LayoutProps<"/">) {
  return (
    <AuthGate>
      <AppHeader />
      <main className="mx-auto flex w-full max-w-5xl flex-1 flex-col px-6 py-8">{children}</main>
    </AuthGate>
  );
}
