import { redirect } from 'next/navigation';
import { auth, signIn } from '@/auth';

export default async function LoginPage() {
  const session = await auth();
  if (session) redirect('/');

  return (
    <div style={{ display: 'grid', placeItems: 'center', minHeight: '70vh' }}>
      <form
        action={async () => {
          'use server';
          await signIn('pocketid', { redirectTo: '/' });
        }}
      >
        <button className="primary" type="submit">
          ログイン
        </button>
      </form>
    </div>
  );
}
