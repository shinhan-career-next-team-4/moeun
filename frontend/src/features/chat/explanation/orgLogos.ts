import miraeAssetLogo from "../../../assets/org-logos/mirae-asset.png";
import samsungAssetLogo from "../../../assets/org-logos/samsung-asset.png";

/**
 * 출처 기관(`source_org.name`)별 로고(#375). 출처 표시에만 쓰는 각 운용사 공식 사이트 아이콘을 64px로 줄인 정적 파일이다.
 * - 삼성자산운용: https://www.samsungfund.com/assets/icons/icon_fund.png (2026-11-24 받음)
 * - 미래에셋자산운용: https://investments.miraeasset.com/favicon.ico 안의 PNG (2026-11-24 받음)
 * 시연은 로컬이라 외부 주소에서 그때그때 불러오지 않는다. 없는 기관은 화면이 이름 첫 글자 배지로 대신한다.
 */
const ORG_LOGOS: Readonly<Record<string, string>> = {
  삼성자산운용: samsungAssetLogo,
  미래에셋자산운용: miraeAssetLogo,
};

export function orgLogo(orgName: string): string | undefined {
  return ORG_LOGOS[orgName];
}
