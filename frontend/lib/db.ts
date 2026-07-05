import { Pool } from 'pg';

// Next.js のホットリロードでプールが増殖しないように global に保持
const globalForPg = globalThis as unknown as { pgPool?: Pool };

export const pool =
  globalForPg.pgPool ??
  new Pool({ connectionString: process.env.DATABASE_URL, max: 5 });

globalForPg.pgPool = pool;
