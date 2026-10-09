"use client";

import { useCallback, useState } from "react";
import { lab, type LabResult } from "./client";
import { useLive, type LiveMode } from "./useLive";

/** Polls one GET endpoint (default every second) and keeps the latest successful body and the last result. */
export function useLabJson<T>(
  path: string,
  params?: Record<string, string | number | undefined | null>,
  intervalMs = 1000,
  enabled = true,
): { data: T | null; last: LabResult<T> | null; mode: LiveMode; refresh: () => Promise<void> } {
  const [data, setData] = useState<T | null>(null);
  const [last, setLast] = useState<LabResult<T> | null>(null);
  const paramsKey = JSON.stringify(params ?? {});

  const fetchOnce = useCallback(async () => {
    const res = await lab.get<T>(path, JSON.parse(paramsKey) as Record<string, string>);
    setLast(res);
    if (res.ok && res.data !== null) setData(res.data);
  }, [path, paramsKey]);

  const mode = useLive({ enabled, poll: fetchOnce, intervalMs });
  return { data, last, mode, refresh: fetchOnce };
}
