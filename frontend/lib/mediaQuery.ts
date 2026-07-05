// /api/media と /api/s/[token]/media で共通のキーセットページネーション処理。
// 両クエリとも media_assets を `m` エイリアスで JOIN しているため共有できる。
export const MEDIA_PAGE_SIZE = 60;

function parseCursorParam(cursor: string): { postedAt: string; mediaKey: string } {
  const sep = cursor.lastIndexOf('|');
  return { postedAt: cursor.slice(0, sep), mediaKey: cursor.slice(sep + 1) };
}

// cursorがあればWHERE条件とプレースホルダ値をその場でconditions/paramsに追加する
export function appendCursorCondition(
  conditions: string[],
  params: unknown[],
  cursor: string | null
): void {
  if (!cursor) return;
  const { postedAt, mediaKey } = parseCursorParam(cursor);
  params.push(postedAt, mediaKey);
  conditions.push(
    `(m.posted_at, m.media_key) < ($${params.length - 1}::timestamptz, $${params.length})`
  );
}

export function nextCursorFor(
  rows: { posted_at: string | Date; media_key: string }[]
): string | null {
  const last = rows[rows.length - 1];
  return last ? `${new Date(last.posted_at).toISOString()}|${last.media_key}` : null;
}
