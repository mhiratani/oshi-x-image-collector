import type { Metadata } from 'next';
import Link from 'next/link';
import { auth, signOut } from '@/auth';
import HeaderNav from '@/components/HeaderNav';
import '../globals.css';

export const metadata: Metadata = {
  title: 'oshi-x-image-collector',
  description: 'X投稿画像の収集・閲覧アプリ (Personal Use)',
};

export default async function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const session = await auth();
  const displayName = session?.user ? session.user.name ?? session.user.email ?? '' : null;

  async function handleSignOut() {
    'use server';
    await signOut();
  }

  return (
    <html lang="ja">
      <body>
        <header className="header">
          <Link href="/" className="brand">
            oshi-x-image-collector
          </Link>
          <HeaderNav displayName={displayName} onSignOut={handleSignOut} />
        </header>
        <main className="main">{children}</main>
      </body>
    </html>
  );
}
