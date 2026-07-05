'use client';

import { useState } from 'react';
import Link from 'next/link';

type Props = {
  displayName: string | null;
  onSignOut: () => Promise<void>;
};

export default function HeaderNav({ displayName, onSignOut }: Props) {
  const [open, setOpen] = useState(false);
  const close = () => setOpen(false);

  return (
    <>
      <button
        className="menu-toggle"
        onClick={() => setOpen((v) => !v)}
        aria-label="メニュー"
        aria-expanded={open}
      >
        {open ? '✕' : '☰'}
      </button>
      <nav className={open ? 'open' : ''}>
        <Link href="/" onClick={close}>
          ギャラリー
        </Link>
        <Link href="/accounts" onClick={close}>
          アカウント管理
        </Link>
        <Link href="/usage" onClick={close}>
          API使用状況
        </Link>
        {displayName && (
          <div className="user-cluster">
            <span className="avatar" title={displayName}>
              {displayName.charAt(0).toUpperCase()}
            </span>
            <span className="user-name">{displayName}</span>
            <form action={onSignOut}>
              <button type="submit" className="logout-btn">
                ログアウト
              </button>
            </form>
          </div>
        )}
      </nav>
    </>
  );
}
