export const LIVE_URL = process.env.LAB_BASE_URL ?? "http://127.0.0.1:8080";

/** Raw reads of the Lab's data browser. They do not go through the MCP server, so they are an independent check. */
export async function browse(table: string, pages = 4): Promise<Record<string, any>[]> {
  const rows: Record<string, any>[] = [];
  for (let page = 0; page < pages; page += 1) {
    const res = await fetch(new URL(`/lab/data/${table}?page=${page}`, LIVE_URL));
    const body = (await res.json()) as { rows: Record<string, any>[]; total: number };
    rows.push(...body.rows);
    if (rows.length >= body.total) break;
  }
  return rows;
}

export async function resetLab(): Promise<boolean> {
  const res = await fetch(new URL("/lab/reset", LIVE_URL), { method: "POST" });
  return res.status === 200;
}
