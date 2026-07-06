import type { NextAuthConfig } from 'next-auth';

// middleware(Edge Runtime)からも使う共通設定。
// firebase-admin などNode専用のモジュールに依存する処理はここに書かない
// (Credentialsプロバイダのauthorize()はauth.ts側にだけ持たせる)。
// middlewareはセッションJWTの検証しか行わずprovider.authorize()を呼ばないため、
// providersは空でよい。
export const authConfig: NextAuthConfig = {
  providers: [],
  session: { strategy: 'jwt' },
  pages: { signIn: '/login' },
};
