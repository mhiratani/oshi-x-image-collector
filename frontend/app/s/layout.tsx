import type { Metadata } from 'next';
import '../globals.css';

export const metadata: Metadata = {
  title: 'oshi-x-image-collector',
  description: 'X投稿画像の収集・閲覧アプリ (Personal Use)',
};

// 共有リンク経由の公開ページ専用のroot layout。
// ログインが必要な他画面（ギャラリー/アカウント管理/API使用状況）への
// 遷移導線は見せない
export default function ShareLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ja">
      <body>
        <header className="header">
          <span className="brand">oshi-x-image-collector</span>
        </header>
        <main className="main">{children}</main>
      </body>
    </html>
  );
}
