'use client';

import { useEffect, useState } from 'react';

type Account = {
  screen_name: string;
  x_user_id: string | null;
  last_fetched_id: string | null;
  media_count: number;
  created_at: string;
  share_token: string | null;
};

export default function AccountsPage() {
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [input, setInput] = useState('');
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);
  const [shareBusy, setShareBusy] = useState<string | null>(null);

  const load = () =>
    fetch('/api/accounts')
      .then((r) => r.json())
      .then((j) => setAccounts(j.accounts ?? []));

  useEffect(() => {
    load();
  }, []);

  const setShareToken = (screenName: string, token: string | null) =>
    setAccounts((prev) =>
      prev.map((a) => (a.screen_name === screenName ? { ...a, share_token: token } : a))
    );

  const createShareLink = async (screenName: string) => {
    setShareBusy(screenName);
    try {
      const res = await fetch('/api/share', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ screenName }),
      });
      const json = await res.json();
      if (res.ok) {
        setShareToken(screenName, json.token);
      } else {
        alert(json.error ?? '共有リンクの発行に失敗しました');
      }
    } finally {
      setShareBusy(null);
    }
  };

  const revokeShareLink = async (screenName: string, token: string) => {
    if (!confirm(`@${screenName} の共有リンクを無効化しますか？（発行済みのURLは使えなくなります）`)) return;
    setShareBusy(screenName);
    try {
      const res = await fetch(`/api/share?token=${encodeURIComponent(token)}`, { method: 'DELETE' });
      if (res.ok) {
        setShareToken(screenName, null);
      } else {
        const json = await res.json().catch(() => ({}));
        alert(json.error ?? '共有リンクの無効化に失敗しました');
      }
    } finally {
      setShareBusy(null);
    }
  };

  const copyShareLink = (token: string) => {
    const url = `${window.location.origin}/s/${token}`;
    navigator.clipboard.writeText(url).catch(() => {});
  };

  const addAccount = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setSaving(true);
    try {
      const res = await fetch('/api/accounts', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ screenName: input }),
      });
      const json = await res.json();
      if (!res.ok) {
        setError(json.error ?? '登録に失敗しました');
        return;
      }
      setInput('');
      load();
    } finally {
      setSaving(false);
    }
  };

  const removeAccount = async (screenName: string) => {
    if (!confirm(`@${screenName} と収集済み画像レコードを削除しますか？`)) return;
    await fetch(`/api/accounts?screenName=${encodeURIComponent(screenName)}`, {
      method: 'DELETE',
    });
    load();
  };

  return (
    <>
      <div className="card">
        <h2>収集対象アカウントを追加</h2>
        <form className="form-row" onSubmit={addAccount}>
          <input
            value={input}
            onChange={(e) => setInput(e.target.value)}
            placeholder="@screen_name（例: @fruits_zipper）"
          />
          <button className="primary" disabled={saving}>
            追加
          </button>
        </form>
        {error && <p className="error-msg">{error}</p>}
        <p className="note">
          登録後、ギャラリー上部に表示される「取得を開始」ボタンを押すと
          収集が始まります（ユーザーIDの解決も同時に行われます）。
          以降の新着チェックは15分ごとに自動実行され、新着があれば
          ギャラリーに「最新を取得」ボタンが表示されます。
        </p>
      </div>

      <div className="card">
        <h2>登録済みアカウント</h2>
        <div className="table-scroll">
          <table className="accounts">
            <thead>
              <tr>
                <th>アカウント</th>
                <th>X User ID</th>
                <th>収集画像数</th>
                <th>最終取得ID</th>
                <th>共有リンク</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {accounts.map((a) => {
                const busy = shareBusy === a.screen_name;
                return (
                  <tr key={a.screen_name}>
                    <td>@{a.screen_name}</td>
                    <td>{a.x_user_id ?? '（未解決）'}</td>
                    <td>{a.media_count}</td>
                    <td>{a.last_fetched_id ?? '—'}</td>
                    <td>
                      {a.share_token ? (
                        <div style={{ display: 'flex', gap: 6 }}>
                          <button
                            className="chip"
                            disabled={busy}
                            onClick={() => copyShareLink(a.share_token!)}
                          >
                            🔗 コピー
                          </button>
                          <button
                            className="danger"
                            disabled={busy}
                            onClick={() => revokeShareLink(a.screen_name, a.share_token!)}
                          >
                            無効化
                          </button>
                        </div>
                      ) : (
                        <button
                          className="chip"
                          disabled={busy}
                          onClick={() => createShareLink(a.screen_name)}
                        >
                          発行
                        </button>
                      )}
                    </td>
                    <td>
                      <button className="danger" onClick={() => removeAccount(a.screen_name)}>
                        削除
                      </button>
                    </td>
                  </tr>
                );
              })}
              {accounts.length === 0 && (
                <tr>
                  <td colSpan={6} style={{ color: 'var(--muted)' }}>
                    まだアカウントが登録されていません
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </div>
    </>
  );
}
