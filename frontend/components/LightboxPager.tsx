'use client';

import IdolImage from '@/components/IdolImage';
import type { LightboxPagerApi } from '@/lib/useLightboxPager';
import type { LightboxZoomApi } from '@/lib/useLightboxZoom';

type PagerItem = {
  media_key: string;
  x_cdn_url: string;
  r2_backup_url: string | null;
};

// 拡大表示の画像部分。前後の画像を左右に並べておき、useLightboxPagerの移動量に合わせて
// トラックごと動かすことで、Androidアプリと同じくスワイプに追従する画像送りにする。
// ズームの変形（useLightboxZoom）は表示中のページにだけ適用する。
export default function LightboxPager<T extends PagerItem>({
  pager,
  zoom,
}: {
  pager: LightboxPagerApi<T>;
  zoom: LightboxZoomApi;
}) {
  return (
    <div className="lightbox-pager" ref={pager.containerRef}>
      <div className="lightbox-track" style={pager.trackStyle}>
        {pager.pages.map(({ key, item, style }) => {
          const isCurrent = key === pager.currentKey;
          return (
            <div className="lightbox-page" key={key} style={style}>
              <div
                className="zoomable"
                ref={isCurrent ? zoom.targetRef : undefined}
                style={isCurrent ? zoom.style : undefined}
              >
                <IdolImage
                  xCdnUrl={item.x_cdn_url}
                  r2BackupUrl={item.r2_backup_url}
                  altText="拡大画像"
                  size="orig"
                />
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
