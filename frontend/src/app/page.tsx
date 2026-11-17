"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";

import { entryPath } from "@/features/auth";
import { me } from "@/lib/api/endpoints";

/** 첫 진입: 로그인 상태와 계획 보유 여부로 화면을 고른다(FR-PLAN-06). */
export default function Home() {
  const router = useRouter();

  useEffect(() => {
    me()
      .then((session) => router.replace(entryPath(session.hasPlan)))
      .catch(() => router.replace("/login"));
  }, [router]);

  return null;
}
