import { S3Client, PutObjectCommand, GetObjectCommand } from '@aws-sdk/client-s3';

// Cloudflare R2 は S3 互換API。region は使われないが SDK が必須とするため 'auto' を渡す
export const r2 = new S3Client({
  region: 'auto',
  endpoint: process.env.CLOUDFLARE_ACCOUNT_ENDPOINT,
  credentials: {
    accessKeyId: process.env.CLOUDFLARE_ACCOUNT_ACCESS_KEY_ID!,
    secretAccessKey: process.env.CLOUDFLARE_ACCOUNT_API_SECRET!,
  },
});

export const R2_BUCKET = process.env.CLOUDFLARE_R2_BUCKET_NAME!;

export async function putObject(key: string, body: Buffer, contentType?: string) {
  await r2.send(
    new PutObjectCommand({ Bucket: R2_BUCKET, Key: key, Body: body, ContentType: contentType })
  );
}

// GetObjectCommand の Body(ByteStream) を Buffer 化する。faceDetect.js の sharp 入力用
export async function getObjectBuffer(key: string): Promise<Buffer> {
  const res = await r2.send(new GetObjectCommand({ Bucket: R2_BUCKET, Key: key }));
  const bytes = await res.Body!.transformToByteArray();
  return Buffer.from(bytes);
}

// app/backups/[...path]/route.ts の配信用。Body をそのまま Response に渡せる形で返す
export async function getObject(key: string) {
  return r2.send(new GetObjectCommand({ Bucket: R2_BUCKET, Key: key }));
}
