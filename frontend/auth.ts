import NextAuth from 'next-auth';
import { upsertAppUser } from '@/lib/repo/appUsers';
import { authConfig } from './auth.config';

export const { handlers, auth, signIn, signOut } = NextAuth({
  ...authConfig,
  callbacks: {
    // ログイン成功時にapp_usersへupsert（推しリストの持ち主をemailで紐づけるため）
    // ここはNode.jsランタイムでしか実行されない（route handler等）ので firebase-admin を使ってよい
    async signIn({ user }) {
      if (!user.email) return false;
      await upsertAppUser(user.email, user.name);
      return true;
    },
  },
});
