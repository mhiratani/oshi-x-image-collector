import type { NextAuthConfig } from 'next-auth';

// middleware(Edge Runtime)からも使う共通設定。
// pg などNode専用のモジュールに依存する処理はここに書かない
// （DBアクセスを伴うsignInコールバックは auth.ts 側にだけ持たせる）。
// ユーザー識別はsession.user.emailで行うため、jwt/sessionのカスタムコールバックは不要
// （NextAuthのデフォルトでsession.user.emailは伝播される）。
export const authConfig: NextAuthConfig = {
  providers: [
    {
      id: 'pocketid',
      name: 'Pocket ID',
      type: 'oidc',
      issuer: process.env.OIDC_ISSUER,
      clientId: process.env.OIDC_CLIENT_ID,
      clientSecret: process.env.OIDC_CLIENT_SECRET,
    },
  ],
  session: { strategy: 'jwt' },
};
