import { NextRequest, NextResponse } from 'next/server';
import { randomBytes } from 'crypto';
import { auth } from '@/auth';
import * as targetAccounts from '@/lib/repo/targetAccounts';
import * as shareLinks from '@/lib/repo/shareLinks';

export const dynamic = 'force-dynamic';

// POST /api/share  body: { screenName }
// 推しリストに入れているアカウントに対して共有リンクを発行する。
// 既に有効なリンクがあればそれを使い回す（無闇に増やさない）
export async function POST(req: NextRequest) {
  const session = await auth();
  const uid = session!.user!.uid!;

  const body = await req.json().catch(() => null);
  const screenName = typeof body?.screenName === 'string' ? body.screenName : '';

  const account = await targetAccounts.get(uid, screenName);
  if (!account) {
    return NextResponse.json({ error: '推しリストに登録されていないアカウントです' }, { status: 404 });
  }

  const existing = await shareLinks.findActiveToken(screenName);
  if (existing) {
    return NextResponse.json({ token: existing });
  }

  const token = randomBytes(24).toString('base64url');
  await shareLinks.create(token, screenName, uid);
  return NextResponse.json({ token });
}

// DELETE /api/share?token=xxx — 共有リンクを無効化する
export async function DELETE(req: NextRequest) {
  const session = await auth();
  const uid = session!.user!.uid!;
  const token = req.nextUrl.searchParams.get('token');
  if (!token) {
    return NextResponse.json({ error: 'token は必須です' }, { status: 400 });
  }

  const link = await shareLinks.getByToken(token);
  const owned = link && link.owner_uid === uid;
  if (!owned || !(await shareLinks.revoke(token))) {
    return NextResponse.json({ error: '対象のリンクが見つかりません（既に無効化済みの可能性があります）' }, { status: 404 });
  }
  return NextResponse.json({ ok: true });
}
