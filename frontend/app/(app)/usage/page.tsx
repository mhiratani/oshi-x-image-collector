'use client';

import { useEffect, useState } from 'react';

type Totals = {
  today: string;
  month: string;
  all_time: string;
  month_calls: number;
  month_quantity: number;
};
type PurposeRow = { purpose: string; calls: number; quantity: number; cost: string };
type DailyRow = { date: string; calls: number; quantity: number; cost: string };
type PeriodPurposeRow = { period: string; purpose: string; calls: number };
type LogRow = {
  id: number;
  called_at: string;
  purpose: string;
  endpoint: string;
  screen_name: string | null;
  resource: string;
  quantity: number;
  cost_usd: string;
};

type UsageData = {
  totals: Totals;
  byPurpose: PurposeRow[];
  daily: DailyRow[];
  dailyByPurpose: PeriodPurposeRow[];
  monthlyByPurpose: PeriodPurposeRow[];
  recent: LogRow[];
};

const PURPOSES = ['resolve', 'check', 'collect', 'backfill'] as const;

const PURPOSE_LABEL: Record<string, string> = {
  resolve: 'ID解決',
  check: '新着チェック',
  collect: '新規収集',
  backfill: 'バックフィル',
};

// [{period, purpose, calls}]の縦持ちデータを 期間 × 用途 の表に組み替える
function pivotByPurpose(rows: PeriodPurposeRow[]) {
  const periods: string[] = [];
  const table = new Map<string, Record<string, number>>();
  for (const row of rows) {
    if (!table.has(row.period)) {
      periods.push(row.period);
      table.set(row.period, {});
    }
    table.get(row.period)![row.purpose] = row.calls;
  }
  return periods.map((period) => {
    const counts = table.get(period)!;
    const total = PURPOSES.reduce((sum, p) => sum + (counts[p] ?? 0), 0);
    return { period, counts, total };
  });
}

const RESOURCE_LABEL: Record<string, string> = {
  user_read: 'User: Read',
  posts_read: 'Posts: Read',
};

function usd(n: string | number): string {
  return `$${Number(n).toFixed(4)}`;
}

export default function UsagePage() {
  const [data, setData] = useState<UsageData | null>(null);

  useEffect(() => {
    fetch('/api/usage')
      .then((r) => r.json())
      .then(setData);
  }, []);

  if (!data) {
    return <p className="status">読み込み中…</p>;
  }

  const maxDaily = Math.max(0.0001, ...data.daily.map((d) => Number(d.cost)));
  const todayDaily = data.daily[data.daily.length - 1] ?? { calls: 0, cost: 0 };
  const last30Calls = data.daily.reduce((sum, d) => sum + d.calls, 0);
  const last30Cost = data.daily.reduce((sum, d) => sum + Number(d.cost), 0);

  return (
    <>
      <div className="stat-row">
        <div className="stat-tile">
          <span className="stat-label">今日</span>
          <span className="stat-value">{usd(data.totals.today)}</span>
        </div>
        <div className="stat-tile">
          <span className="stat-label">今月</span>
          <span className="stat-value">{usd(data.totals.month)}</span>
        </div>
        <div className="stat-tile">
          <span className="stat-label">累計</span>
          <span className="stat-value">{usd(data.totals.all_time)}</span>
        </div>
        <div className="stat-tile">
          <span className="stat-label">今月の呼び出し回数</span>
          <span className="stat-value">{data.totals.month_calls}</span>
        </div>
        <div className="stat-tile">
          <span className="stat-label">今月の読み取り件数</span>
          <span className="stat-value">{data.totals.month_quantity}</span>
        </div>
      </div>
      <p className="note">
        X APIは従量課金（返却されたリソース件数 × 単価）です。ここでの金額は
        Posts: Read $0.005 / User: Read $0.010（読み取り1件あたり）を基にした概算で、
        X側の24時間重複排除は考慮していません。正確な請求額は
        <a href="https://console.x.com" target="_blank" rel="noreferrer">
          Developer Console
        </a>
        を参照してください。
      </p>

      <div className="card">
        <h2>直近30日の推移</h2>
        <p className="note" style={{ marginTop: 0, marginBottom: 12 }}>
          今日: {todayDaily.calls}回（{usd(todayDaily.cost)}）　／　直近30日: {last30Calls}回（
          {usd(last30Cost)}）
        </p>
        <div className="usage-bars">
          {data.daily.map((d) => (
            <div className="bar-col" key={d.date}>
              <div className="bar-tip">
                {new Date(d.date).toLocaleDateString('ja-JP', { month: 'numeric', day: 'numeric' })}
                <br />
                呼び出し {d.calls}回 / {d.quantity}件
                <br />
                {usd(d.cost)}
              </div>
              <div
                className="bar"
                style={{ height: `${(Number(d.cost) / maxDaily) * 100}%` }}
              />
            </div>
          ))}
        </div>
      </div>

      <div className="card">
        <h2>用途別 呼び出し回数（日次・直近30日）</h2>
        <div className="table-scroll">
          <table className="data-table">
            <thead>
              <tr>
                <th>日付</th>
                {PURPOSES.map((p) => (
                  <th key={p}>{PURPOSE_LABEL[p]}</th>
                ))}
                <th>合計</th>
              </tr>
            </thead>
            <tbody>
              {[...pivotByPurpose(data.dailyByPurpose)].reverse().map((row) => (
                <tr key={row.period}>
                  <td>{new Date(row.period).toLocaleDateString('ja-JP')}</td>
                  {PURPOSES.map((p) => (
                    <td key={p}>{row.counts[p] ?? 0}</td>
                  ))}
                  <td>{row.total}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      <div className="card">
        <h2>用途別 呼び出し回数（月次・直近12ヶ月）</h2>
        <div className="table-scroll">
          <table className="data-table">
            <thead>
              <tr>
                <th>月</th>
                {PURPOSES.map((p) => (
                  <th key={p}>{PURPOSE_LABEL[p]}</th>
                ))}
                <th>合計</th>
              </tr>
            </thead>
            <tbody>
              {[...pivotByPurpose(data.monthlyByPurpose)].reverse().map((row) => (
                <tr key={row.period}>
                  <td>
                    {new Date(row.period).toLocaleDateString('ja-JP', {
                      year: 'numeric',
                      month: 'long',
                    })}
                  </td>
                  {PURPOSES.map((p) => (
                    <td key={p}>{row.counts[p] ?? 0}</td>
                  ))}
                  <td>{row.total}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      <div className="card">
        <h2>今月の内訳</h2>
        <div className="table-scroll">
          <table className="data-table">
            <thead>
              <tr>
                <th>用途</th>
                <th>呼び出し回数</th>
                <th>読み取り件数</th>
                <th>コスト</th>
              </tr>
            </thead>
            <tbody>
              {data.byPurpose.map((p) => (
                <tr key={p.purpose}>
                  <td>{PURPOSE_LABEL[p.purpose] ?? p.purpose}</td>
                  <td>{p.calls}</td>
                  <td>{p.quantity}</td>
                  <td>{usd(p.cost)}</td>
                </tr>
              ))}
              {data.byPurpose.length === 0 && (
                <tr>
                  <td colSpan={4} style={{ color: 'var(--muted)' }}>
                    今月はまだAPI呼び出しがありません
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </div>

      <div className="card">
        <h2>呼び出し履歴（直近50件）</h2>
        <div className="table-scroll">
          <table className="data-table">
            <thead>
              <tr>
                <th>日時</th>
                <th>用途</th>
                <th>アカウント</th>
                <th>区分</th>
                <th>件数</th>
                <th>コスト</th>
              </tr>
            </thead>
            <tbody>
              {data.recent.map((r) => (
                <tr key={r.id}>
                  <td>{new Date(r.called_at).toLocaleString('ja-JP')}</td>
                  <td>{PURPOSE_LABEL[r.purpose] ?? r.purpose}</td>
                  <td>{r.screen_name ? `@${r.screen_name}` : '—'}</td>
                  <td>{RESOURCE_LABEL[r.resource] ?? r.resource}</td>
                  <td>{r.quantity}</td>
                  <td>{usd(r.cost_usd)}</td>
                </tr>
              ))}
              {data.recent.length === 0 && (
                <tr>
                  <td colSpan={6} style={{ color: 'var(--muted)' }}>
                    まだ記録がありません
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </div>
    </>
  );
}
