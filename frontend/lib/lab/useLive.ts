"use client";

import { useEffect, useRef, useState } from "react";

export type LiveMode = "sse" | "polling" | "connecting" | "paused";

interface Options {
  /** Turn the whole thing off (for example while a log tail is paused, or no run is selected). */
  enabled: boolean;
  /** Called once per second with a fresh snapshot while polling. Return nothing; push into your own state. */
  poll: () => Promise<void> | void;
  intervalMs?: number;
  /** Optional server-sent-event stream. When it opens, polling stops; when it fails or never opens, polling takes over. */
  stream?: {
    url: string;
    /** Event names to listen for. */
    events: string[];
    onEvent: (name: string, data: string) => void;
  } | null;
  /** How long to wait for the stream to open before falling back to polling (ingress controllers can buffer streams). */
  openTimeoutMs?: number;
}

/**
 * One hook for every live panel: SSE when it works, 1-second polling when it does not. Nothing here animates or
 * ticks on its own: state only changes when the server sent something.
 */
export function useLive({ enabled, poll, intervalMs = 1000, stream = null, openTimeoutMs = 3000 }: Options): LiveMode {
  const [mode, setMode] = useState<LiveMode>("connecting");
  const pollRef = useRef(poll);
  const streamRef = useRef(stream);
  useEffect(() => {
    pollRef.current = poll;
    streamRef.current = stream;
  });
  const streamUrl = stream?.url ?? null;

  useEffect(() => {
    if (!enabled) return;
    let cancelled = false;
    let source: EventSource | null = null;
    let timer: ReturnType<typeof setInterval> | null = null;
    let openTimer: ReturnType<typeof setTimeout> | null = null;

    const startPolling = () => {
      if (cancelled || timer) return;
      setMode("polling");
      void pollRef.current();
      timer = setInterval(() => void pollRef.current(), intervalMs);
    };

    const config = streamRef.current;
    if (config && typeof EventSource !== "undefined") {
      source = new EventSource(config.url);
      openTimer = setTimeout(() => {
        // never opened: something between us and the server is probably buffering the stream
        source?.close();
        source = null;
        startPolling();
      }, openTimeoutMs);
      source.onopen = () => {
        if (openTimer) clearTimeout(openTimer);
        if (!cancelled) setMode("sse");
      };
      source.onerror = () => {
        source?.close();
        source = null;
        if (openTimer) clearTimeout(openTimer);
        startPolling();
      };
      for (const name of config.events) {
        source.addEventListener(name, (e) => streamRef.current?.onEvent(name, (e as MessageEvent).data));
      }
    } else {
      startPolling();
    }

    return () => {
      cancelled = true;
      if (openTimer) clearTimeout(openTimer);
      if (timer) clearInterval(timer);
      source?.close();
    };
  }, [enabled, intervalMs, streamUrl, openTimeoutMs]);

  return enabled ? mode : "paused";
}
