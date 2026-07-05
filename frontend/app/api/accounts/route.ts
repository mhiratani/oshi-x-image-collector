import { NextRequest, NextResponse } from 'next/server';
import { auth } from '@/auth';
import * as targetAccounts from '@/lib/repo/targetAccounts';
import * as subscriptions from '@/lib/repo/subscriptions';
import * as media from '@/lib/repo/media';
import * as shareLinks from '@/lib/repo/shareLinks';
import { getSubscribedAccounts } from '@/lib/repo/userAccounts';

export const dynamic = 'force-dynamic';

// GET /api/accounts — ログインユーザーが推しリストに入れているアカウント一覧
// （画像数・有効な共有リンクのtoken付き）
export async function GET() {
  const session = await auth();
  const userEmail = session!.user!.email!;

  const accounts = await getSubscribedAccounts(userEmail);
  const rows = await Promise.all(
    accounts.map(async (a) => ({
      screen_name: a.screen_name,
      x_user_id: a.x_user_id,
      last_fetched_id: a.last_fetched_id,
      created_at: a.subscribed_at,
      media_count: a.x_user_id ? await media.countForXUserId(a.x_user_id) : 0,
      share_token: await shareLinks.findActiveToken(a.screen_name),
    }))
  );
  return NextResponse.json({ accounts: rows });
}

// POST /api/accounts  body: { screenName: "@fruits_zipper" }
// target_accounts はユーザー間で共有。呼び出したユーザーの推しリストに
// 紐づけるのは user_subscriptions。x_user_id はワーカーが次回バッチで自動解決する
export async function POST(req: NextRequest) {
  const session = await auth();
  const userEmail = session!.user!.email!;

  const body = await req.json().catch(() => null);
  const raw = typeof body?.screenName === 'string' ? body.screenName : '';
  const screenName = raw.trim().replace(/^@/, '');

  if (!/^[A-Za-z0-9_]{1,15}$/.test(screenName)) {
    return NextResponse.json(
      { error: 'screen_name の形式が不正です（英数字と _ のみ、15文字以内）' },
      { status: 400 }
    );
  }

  await targetAccounts.createIfNotExists(screenName);
  await subscriptions.addSubscription(userEmail, screenName);
  return NextResponse.json({ ok: true, screenName });
}

// DELETE /api/accounts?screenName=xxx
// 呼び出したユーザーの推しリストから外す。他に誰も推していないアカウントに
// なった場合のみ target_accounts 自体を削除（収集画像も CASCADE 削除）
export async function DELETE(req: NextRequest) {
  const session = await auth();
  const userEmail = session!.user!.email!;

  const screenName = req.nextUrl.searchParams.get('screenName');
  if (!screenName) {
    return NextResponse.json({ error: 'screenName は必須です' }, { status: 400 });
  }

  await subscriptions.removeSubscription(userEmail, screenName);
  const remaining = await subscriptions.countSubscribers(screenName);
  if (remaining === 0) {
    await targetAccounts.deleteCascade(screenName);
  }
  return NextResponse.json({ ok: true });
}
