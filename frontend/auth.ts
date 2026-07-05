import NextAuth from 'next-auth';
import { pool } from '@/lib/db';
import { authConfig } from './auth.config';

export const { handlers, auth, signIn, signOut } = NextAuth({
  ...authConfig,
  callbacks: {
    // ログイン成功時にapp_usersへupsert（推しリストの持ち主をemailで紐づけるため）
    // ここはNode.jsランタイムでしか実行されない（route handler等）ので pg を使ってよい
    async signIn({ user }) {
      if (!user.email) return false;
      await pool.query(
        `INSERT INTO app_users (email, name) VALUES ($1, $2)
         ON CONFLICT (email) DO UPDATE SET name = EXCLUDED.name`,
        [user.email, user.name]
      );
      return true;
    },
  },
});
