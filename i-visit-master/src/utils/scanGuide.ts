// src/utils/scanGuide.ts

export const CARD_ASPECT = 1.586;       // ID-1 aspect ratio
export const GUIDE_W_FRAC = 0.88;       // matches your overlay
export const GUIDE_MAX_H_FRAC = 0.85;   // matches your overlay

export function computeGuideRect(
  width: number,
  height: number,
  cardAspect: number = CARD_ASPECT,
  guideWFrac: number = GUIDE_W_FRAC,
  guideMaxHFrac: number = GUIDE_MAX_H_FRAC
) {
  let guideWidth = width * guideWFrac;
  let guideHeight = guideWidth / cardAspect;

  if (guideHeight > height * guideMaxHFrac) {
    guideHeight = height * guideMaxHFrac;
    guideWidth = guideHeight * cardAspect;
  }

  const x = (width - guideWidth) / 2;
  const y = (height - guideHeight) / 2;

  return {
    x: Math.round(x),
    y: Math.round(y),
    width: Math.round(guideWidth),
    height: Math.round(guideHeight),
  };
}