import { NextRequest } from 'next/server';
import * as media from '@/lib/repo/media';
import { likeTweet } from '@/lib/xWrite';
import { handleXAction } from '../xAction';

export const dynamic = 'force-dynamic';

// POST /api/media/:mediaKey/like
// 画像の元ツイートへ自分のアカウントとしていいねを送る。
// いいねはトグル操作のため、送信済み（liked_on_x）ならX APIを呼ばず何もしない
export async function POST(
  _req: NextRequest,
  { params }: { params: { mediaKey: string } }
) {
  return handleXAction(params.mediaKey, {
    skipIf: (target) => target.liked_on_x,
    send: likeTweet,
    mark: media.markTweetLiked,
  });
}
