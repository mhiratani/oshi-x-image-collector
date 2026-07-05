import { NextResponse } from 'next/server';
import { pool } from '@/lib/db';

export const dynamic = 'force-dynamic';

// GET /api/usage — X APIの呼び出し履歴とコスト（従量課金）の集計
// 認証チェックはmiddleware.tsが全ルートに適用済み
export async function GET() {
  const [totals, byPurpose, daily, dailyByPurpose, monthlyByPurpose, recent] = await Promise.all([
    pool.query(
      `SELECT
         coalesce(sum(cost_usd) FILTER (WHERE called_at >= date_trunc('day', now())), 0) AS today,
         coalesce(sum(cost_usd) FILTER (WHERE called_at >= date_trunc('month', now())), 0) AS month,
         coalesce(sum(cost_usd), 0) AS all_time,
         coalesce(count(*) FILTER (WHERE called_at >= date_trunc('month', now())), 0)::int AS month_calls,
         coalesce(sum(quantity) FILTER (WHERE called_at >= date_trunc('month', now())), 0)::int AS month_quantity
       FROM api_usage_log`
    ),
    pool.query(
      `SELECT purpose, count(*)::int AS calls, sum(quantity)::int AS quantity, sum(cost_usd) AS cost
         FROM api_usage_log
        WHERE called_at >= date_trunc('month', now())
        GROUP BY purpose
        ORDER BY cost DESC`
    ),
    pool.query(
      `SELECT d::date AS date,
              count(l.id)::int AS calls,
              coalesce(sum(l.quantity), 0)::int AS quantity,
              coalesce(sum(l.cost_usd), 0) AS cost
         FROM generate_series(date_trunc('day', now()) - interval '29 days', date_trunc('day', now()), interval '1 day') d
         LEFT JOIN api_usage_log l ON date_trunc('day', l.called_at) = d
        GROUP BY d
        ORDER BY d`
    ),
    // 用途別コール数の日次内訳（直近30日 × purpose のグリッド。呼び出しが無い組み合わせも0で埋める）
    pool.query(
      `SELECT d::date AS period, pu.purpose, count(l.id)::int AS calls
         FROM generate_series(date_trunc('day', now()) - interval '29 days', date_trunc('day', now()), interval '1 day') d
        CROSS JOIN (VALUES ('resolve'), ('check'), ('collect'), ('backfill')) AS pu(purpose)
         LEFT JOIN api_usage_log l
           ON date_trunc('day', l.called_at) = d AND l.purpose = pu.purpose
        GROUP BY d, pu.purpose
        ORDER BY d, pu.purpose`
    ),
    // 用途別コール数の月次内訳（直近12ヶ月 × purpose のグリッド）
    pool.query(
      `SELECT m::date AS period, pu.purpose, count(l.id)::int AS calls
         FROM generate_series(date_trunc('month', now()) - interval '11 months', date_trunc('month', now()), interval '1 month') m
        CROSS JOIN (VALUES ('resolve'), ('check'), ('collect'), ('backfill')) AS pu(purpose)
         LEFT JOIN api_usage_log l
           ON date_trunc('month', l.called_at) = m AND l.purpose = pu.purpose
        GROUP BY m, pu.purpose
        ORDER BY m, pu.purpose`
    ),
    pool.query(
      `SELECT id, called_at, purpose, endpoint, screen_name, resource, quantity, cost_usd
         FROM api_usage_log
        ORDER BY called_at DESC
        LIMIT 50`
    ),
  ]);

  return NextResponse.json({
    totals: totals.rows[0],
    byPurpose: byPurpose.rows,
    daily: daily.rows,
    dailyByPurpose: dailyByPurpose.rows,
    monthlyByPurpose: monthlyByPurpose.rows,
    recent: recent.rows,
  });
}
