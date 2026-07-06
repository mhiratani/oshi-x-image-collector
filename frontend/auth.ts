import NextAuth from 'next-auth';
import Credentials from 'next-auth/providers/credentials';
import { authConfig } from './auth.config';
import { verifyFirebaseIdToken } from '@/lib/auth/verifyFirebaseIdToken';

export const { handlers, auth, signIn, signOut } = NextAuth({
  ...authConfig,
  providers: [
    Credentials({
      id: 'firebase',
      name: 'Firebase',
      credentials: { idToken: { label: 'idToken', type: 'text' } },
      async authorize(credentials) {
        const idToken = credentials?.idToken;
        if (typeof idToken !== 'string' || !idToken) return null;
        try {
          const decoded = await verifyFirebaseIdToken(idToken);
          return { id: decoded.uid, email: decoded.email ?? null, name: decoded.name ?? null };
        } catch {
          return null;
        }
      },
    }),
  ],
  callbacks: {
    // 本人(Android版で確認済みのFirebase uid)以外のGoogleアカウントでのサインインを拒否する。
    // Google Sign-Inは任意のGoogleアカウントを受け付けてしまうため、これが唯一のアクセス制御になる。
    async signIn({ user }) {
      return user.id === process.env.OWNER_UID;
    },
    async jwt({ token, user }) {
      if (user) token.uid = user.id;
      return token;
    },
    async session({ session, token }) {
      if (session.user) session.user.uid = token.uid as string;
      return session;
    },
  },
});
