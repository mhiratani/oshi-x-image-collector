import { NextRequest } from 'next/server';
import * as media from '@/lib/repo/media';
import { repostTweet } from '@/lib/xWrite';
import { handleXAction } from '../xAction';

export const dynamic = 'force-dynamic';

// POST /api/media/:mediaKey/repost
// 画像の元ツイートを自分のアカウントとしてリポストする。
// リポスト済みでも再送を妨げない（X側で解除した後の再リポストを許すため）
export async function POST(
  _req: NextRequest,
  { params }: { params: { mediaKey: string } }
) {
  return handleXAction(params.mediaKey, {
    send: repostTweet,
    mark: media.markTweetReposted,
  });
}
