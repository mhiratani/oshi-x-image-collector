import { redirect } from 'next/navigation';
import { auth } from '@/auth';
import LoginButton from './LoginButton';

export default async function LoginPage() {
  const session = await auth();
  if (session) redirect('/');

  return (
    <div style={{ display: 'grid', placeItems: 'center', minHeight: '70vh' }}>
      <LoginButton />
    </div>
  );
}
