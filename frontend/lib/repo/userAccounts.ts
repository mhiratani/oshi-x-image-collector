import * as subscriptions from './subscriptions';
import * as targetAccounts from './targetAccounts';

export type UserAccount = {
  screen_name: string;
  x_user_id: string | null;
  last_fetched_id: string | null;
  backfill_done: boolean;
  checked_at: Date | null;
  subscribed_at: Date;
};

// user_subscriptions と target_accounts をまたいだ「ログインユーザーの推しリスト」取得。
// /api/accounts, /api/media, /api/collect, /api/backfill から共通で使う
export async function getSubscribedAccounts(userEmail: string): Promise<UserAccount[]> {
  const subs = await subscriptions.listForUser(userEmail);
  if (subs.length === 0) return [];
  const accounts = await targetAccounts.getMany(subs.map((s) => s.screen_name));
  return subs
    .filter((s) => accounts.has(s.screen_name))
    .map((s) => {
      const a = accounts.get(s.screen_name)!;
      return {
        screen_name: a.screen_name,
        x_user_id: a.x_user_id,
        last_fetched_id: a.last_fetched_id,
        backfill_done: a.backfill_done,
        checked_at: a.checked_at,
        subscribed_at: s.created_at,
      };
    });
}

export async function getSubscribedXUserIds(userEmail: string): Promise<string[]> {
  const accounts = await getSubscribedAccounts(userEmail);
  return accounts.map((a) => a.x_user_id).filter((id): id is string => id !== null);
}
