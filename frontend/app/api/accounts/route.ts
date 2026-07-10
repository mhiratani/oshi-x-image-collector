import { NextRequest, NextResponse } from 'next/server';
import { auth } from '@/auth';
import * as targetAccounts from '@/lib/repo/targetAccounts';
import * as media from '@/lib/repo/media';
import * as shareLinks from '@/lib/repo/shareLinks';

export const dynamic = 'force-dynamic';

// GET /api/accounts — ログインユーザーの推しリストのアカウント一覧
// （画像数・有効な共有リンクのtoken付き）
export async function GET() {
  const session = await auth();
  const uid = session!.user!.uid!;

  const accounts = await targetAccounts.listAll(uid);
  const rows = await Promise.all(
    accounts.map(async (a) => ({
      screen_name: a.screen_name,
      x_user_id: a.x_user_id,
      last_fetched_id: a.last_fetched_id,
      sync_paused: a.sync_paused,
      created_at: a.created_at,
      media_count: a.x_user_id ? await media.countForXUserId(uid, a.x_user_id) : 0,
      share_token: await shareLinks.findActiveToken(a.screen_name),
    }))
  );
  return NextResponse.json({ accounts: rows });
}

// POST /api/accounts  body: { screenName: "@fruits_zipper" }
// x_user_id はワーカーが次回バッチで自動解決する
export async function POST(req: NextRequest) {
  const session = await auth();
  const uid = session!.user!.uid!;

  const body = await req.json().catch(() => null);
  const raw = typeof body?.screenName === 'string' ? body.screenName : '';
  const screenName = raw.trim().replace(/^@/, '');

  if (!/^[A-Za-z0-9_]{1,15}$/.test(screenName)) {
    return NextResponse.json(
      { error: 'screen_name の形式が不正です（英数字と _ のみ、15文字以内）' },
      { status: 400 }
    );
  }

  await targetAccounts.createIfNotExists(uid, screenName);
  return NextResponse.json({ ok: true, screenName });
}

// PATCH /api/accounts  body: { screenName: string, syncPaused: boolean }
// 同期停止/再開。アカウント削除の概念は無く、追跡をやめたい場合は停止にする
// （収集済みの画像・メタデータは残る）
export async function PATCH(req: NextRequest) {
  const session = await auth();
  const uid = session!.user!.uid!;

  const body = await req.json().catch(() => null);
  const screenName = typeof body?.screenName === 'string' ? body.screenName : '';
  if (!screenName || typeof body?.syncPaused !== 'boolean') {
    return NextResponse.json({ error: 'screenNameとsyncPaused(boolean)が必要です' }, { status: 400 });
  }

  await targetAccounts.setSyncPaused(uid, screenName, body.syncPaused);
  return NextResponse.json({ ok: true });
}
