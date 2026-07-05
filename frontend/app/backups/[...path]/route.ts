import { getObject } from '@/lib/r2';

// バックアップ画像・サムネイルの配信。R2から都度取得してストリーミングで返す
export async function GET(
  _req: Request,
  { params }: { params: { path: string[] } }
) {
  const key = params.path.join('/');

  // 意図しないバケット横断キー指定の防止
  if (key.includes('..')) {
    return new Response('Forbidden', { status: 403 });
  }

  try {
    const obj = await getObject(key);
    return new Response(obj.Body?.transformToWebStream(), {
      headers: {
        'Content-Type': obj.ContentType ?? 'application/octet-stream',
        'Cache-Control': 'public, max-age=604800, immutable',
      },
    });
  } catch {
    return new Response('Not Found', { status: 404 });
  }
}
