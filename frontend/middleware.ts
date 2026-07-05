import NextAuth from 'next-auth';
import { NextResponse } from 'next/server';
import { authConfig } from './auth.config';

// pgを含まないedge-safeな設定だけでmiddleware用のauthインスタンスを作る
const { auth } = NextAuth(authConfig);

export default auth((req) => {
  if (req.auth) return;

  if (req.nextUrl.pathname.startsWith('/api/')) {
    return NextResponse.json({ error: '認証が必要です' }, { status: 401 });
  }
  if (req.nextUrl.pathname !== '/login') {
    return NextResponse.redirect(new URL('/login', req.nextUrl));
  }
});

// /api/auth はNextAuth自身の認証フロー用ルートなので、そもそもこのmiddlewareの
// 対象から外す（含めるとコールバック自体が「未認証」判定でリダイレクトされ、
// トークン交換が一度も走らなくなる）
// /s と /api/s は共有リンクによるログイン不要の公開閲覧ページ・APIなので同様に除外する
export const config = {
  matcher: ['/((?!_next/static|_next/image|favicon.ico|backups|api/auth|s/|api/s/).*)'],
};
