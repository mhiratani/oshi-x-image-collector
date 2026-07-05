import { db } from '@/lib/firestore';
import { AggregateField, FieldValue, Timestamp } from 'firebase-admin/firestore';

const col = () => db.collection('api_usage_log');

const PURPOSES = ['resolve', 'check', 'collect', 'backfill'] as const;

export async function logUsage(entry: {
  purpose: string;
  endpoint: string;
  screenName: string | null;
  resource: string;
  quantity: number;
  unitCostUsd: number;
}): Promise<void> {
  await col().add({
    called_at: FieldValue.serverTimestamp(),
    purpose: entry.purpose,
    endpoint: entry.endpoint,
    screen_name: entry.screenName ?? null,
    resource: entry.resource,
    quantity: entry.quantity,
    unit_cost_usd: entry.unitCostUsd,
    cost_usd: entry.quantity * entry.unitCostUsd,
  });
}

function startOfUtcDay(d: Date): Date {
  return new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate()));
}
function startOfUtcMonth(d: Date): Date {
  return new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), 1));
}
function dayKey(d: Date): string {
  return d.toISOString().slice(0, 10);
}
function monthKey(d: Date): string {
  return `${d.toISOString().slice(0, 7)}-01`;
}

export type UsageStats = {
  totals: {
    today: number;
    month: number;
    all_time: number;
    month_calls: number;
    month_quantity: number;
  };
  byPurpose: { purpose: string; calls: number; quantity: number; cost: number }[];
  daily: { date: string; calls: number; quantity: number; cost: number }[];
  dailyByPurpose: { period: string; purpose: string; calls: number }[];
  monthlyByPurpose: { period: string; purpose: string; calls: number }[];
  recent: {
    id: string;
    called_at: Date;
    purpose: string;
    endpoint: string;
    screen_name: string | null;
    resource: string;
    quantity: number;
    cost_usd: number;
  }[];
};

// X APIの呼び出し履歴とコスト(従量課金)の集計。
// 当日/当月/全期間の合計はFirestoreの集計クエリ(count/sum)で1読み取りずつ求め、
// 日次・月次の内訳は直近12ヶ月分の生ドキュメントを1回だけ取得してJS側で
// 日付バケットに振り分ける（Firestoreに generate_series/GROUP BY 相当が無いため）
export async function getUsageStats(): Promise<UsageStats> {
  const now = new Date();
  const todayStart = startOfUtcDay(now);
  const monthStart = startOfUtcMonth(now);
  const twelveMonthsAgo = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - 11, 1));

  const [allTimeAgg, todayAgg, monthAgg, recentSnap, rangeSnap, ...byPurposeAgg] = await Promise.all([
    col().aggregate({ cost: AggregateField.sum('cost_usd') }).get(),
    col()
      .where('called_at', '>=', Timestamp.fromDate(todayStart))
      .aggregate({ cost: AggregateField.sum('cost_usd') })
      .get(),
    col()
      .where('called_at', '>=', Timestamp.fromDate(monthStart))
      .aggregate({
        cost: AggregateField.sum('cost_usd'),
        quantity: AggregateField.sum('quantity'),
        calls: AggregateField.count(),
      })
      .get(),
    col().orderBy('called_at', 'desc').limit(50).get(),
    col().where('called_at', '>=', Timestamp.fromDate(twelveMonthsAgo)).orderBy('called_at', 'asc').get(),
    ...PURPOSES.map((purpose) =>
      col()
        .where('called_at', '>=', Timestamp.fromDate(monthStart))
        .where('purpose', '==', purpose)
        .aggregate({
          cost: AggregateField.sum('cost_usd'),
          quantity: AggregateField.sum('quantity'),
          calls: AggregateField.count(),
        })
        .get()
    ),
  ]);

  const byPurpose = PURPOSES.map((purpose, i) => {
    const data = byPurposeAgg[i].data();
    return {
      purpose,
      calls: Number(data.calls ?? 0),
      quantity: Number(data.quantity ?? 0),
      cost: Number(data.cost ?? 0),
    };
  })
    .filter((row) => row.calls > 0)
    .sort((a, b) => b.cost - a.cost);

  const rangeRows = rangeSnap.docs.map((d) => {
    const data = d.data();
    return {
      called_at: (data.called_at as Timestamp).toDate(),
      purpose: data.purpose as string,
      quantity: (data.quantity as number) ?? 0,
      cost_usd: (data.cost_usd as number) ?? 0,
    };
  });

  // 直近30日分の日付キー一覧（呼び出しが無い日も0埋めするため先に器を作る）
  const dailyDates: string[] = [];
  for (let i = 29; i >= 0; i--) {
    const d = new Date(todayStart);
    d.setUTCDate(d.getUTCDate() - i);
    dailyDates.push(dayKey(d));
  }
  const dailyMap = new Map(dailyDates.map((date) => [date, { calls: 0, quantity: 0, cost: 0 }]));
  const dailyByPurposeMap = new Map<string, number>();
  for (const date of dailyDates) for (const p of PURPOSES) dailyByPurposeMap.set(`${date}|${p}`, 0);

  // 直近12ヶ月分の月キー一覧
  const monthDates: string[] = [];
  for (let i = 11; i >= 0; i--) {
    const d = new Date(Date.UTC(monthStart.getUTCFullYear(), monthStart.getUTCMonth() - i, 1));
    monthDates.push(monthKey(d));
  }
  const monthlyByPurposeMap = new Map<string, number>();
  for (const period of monthDates) for (const p of PURPOSES) monthlyByPurposeMap.set(`${period}|${p}`, 0);

  for (const row of rangeRows) {
    const dKey = dayKey(row.called_at);
    const daily = dailyMap.get(dKey);
    if (daily) {
      daily.calls += 1;
      daily.quantity += row.quantity;
      daily.cost += row.cost_usd;
      const dpKey = `${dKey}|${row.purpose}`;
      if (dailyByPurposeMap.has(dpKey)) {
        dailyByPurposeMap.set(dpKey, (dailyByPurposeMap.get(dpKey) ?? 0) + 1);
      }
    }
    const mKey = monthKey(startOfUtcMonth(row.called_at));
    const mpKey = `${mKey}|${row.purpose}`;
    if (monthlyByPurposeMap.has(mpKey)) {
      monthlyByPurposeMap.set(mpKey, (monthlyByPurposeMap.get(mpKey) ?? 0) + 1);
    }
  }

  const daily = dailyDates.map((date) => ({ date, ...dailyMap.get(date)! }));
  const dailyByPurpose = dailyDates.flatMap((period) =>
    PURPOSES.map((purpose) => ({ period, purpose, calls: dailyByPurposeMap.get(`${period}|${purpose}`)! }))
  );
  const monthlyByPurpose = monthDates.flatMap((period) =>
    PURPOSES.map((purpose) => ({ period, purpose, calls: monthlyByPurposeMap.get(`${period}|${purpose}`)! }))
  );

  const recent = recentSnap.docs.map((d) => {
    const data = d.data();
    return {
      id: d.id,
      called_at: (data.called_at as Timestamp).toDate(),
      purpose: data.purpose as string,
      endpoint: data.endpoint as string,
      screen_name: (data.screen_name as string | null) ?? null,
      resource: data.resource as string,
      quantity: data.quantity as number,
      cost_usd: data.cost_usd as number,
    };
  });

  return {
    totals: {
      today: Number(todayAgg.data().cost ?? 0),
      month: Number(monthAgg.data().cost ?? 0),
      all_time: Number(allTimeAgg.data().cost ?? 0),
      month_calls: Number(monthAgg.data().calls ?? 0),
      month_quantity: Number(monthAgg.data().quantity ?? 0),
    },
    byPurpose,
    daily,
    dailyByPurpose,
    monthlyByPurpose,
    recent,
  };
}
