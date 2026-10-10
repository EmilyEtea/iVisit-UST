// src/utils/cardCropper.ts
import { waitForOpencv } from "./opencvReady";
import { computeGuideRect } from "./scanGuide";

export interface CropResult {
  success: boolean;
  dataUrl?: string;
  reason?: string;
  sharpness?: number;
}

function computeSharpness(mat: any): number {
  const cv = window.cv;

  const gray = new cv.Mat();
  cv.cvtColor(mat, gray, cv.COLOR_RGBA2GRAY, 0);

  const lap = new cv.Mat();
  cv.Laplacian(gray, lap, cv.CV_64F);

  const mean = new cv.Mat();
  const stddev = new cv.Mat();
  cv.meanStdDev(lap, mean, stddev);

  const sharpness = stddev.doubleAt(0, 0);

  gray.delete();
  lap.delete();
  mean.delete();
  stddev.delete();

  return sharpness;
}

function orderQuadPoints(pts: { x: number; y: number }[]) {
  const sum = pts.map((p) => p.x + p.y);
  const diff = pts.map((p) => p.x - p.y);

  const tl = pts[sum.indexOf(Math.min(...sum))];
  const br = pts[sum.indexOf(Math.max(...sum))];
  const tr = pts[diff.indexOf(Math.max(...diff))];
  const bl = pts[diff.indexOf(Math.min(...diff))];

  return [tl, tr, br, bl] as const;
}

async function dataUrlToCanvas(dataUrl: string): Promise<HTMLCanvasElement> {
  const img = new Image();
  img.src = dataUrl;

  await new Promise<void>((resolve, reject) => {
    img.onload = () => resolve();
    img.onerror = (e) => reject(e);
  });

  const canvas = document.createElement("canvas");
  canvas.width = img.width || 640;
  canvas.height = img.height || 480;

  const ctx = canvas.getContext("2d");
  if (!ctx) throw new Error("Could not get 2D context for canvas");

  ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
  return canvas;
}

export async function cropIdCardFromDataUrl(dataUrl: string): Promise<CropResult> {
  try {
    await waitForOpencv();
    const cv = window.cv;

    const canvas = await dataUrlToCanvas(dataUrl);

    let src = cv.imread(canvas);
    const original = src.clone();

    const maxDim = 1000;
    let scale = 1;
    if (src.cols > maxDim || src.rows > maxDim) {
      const fx = maxDim / src.cols;
      const fy = maxDim / src.rows;
      scale = Math.min(fx, fy);

      const dsize = new cv.Size(
        Math.round(src.cols * scale),
        Math.round(src.rows * scale)
      );

      const resized = new cv.Mat();
      cv.resize(src, resized, dsize, 0, 0, cv.INTER_AREA);
      src.delete();
      src = resized;
    }

    // Limit contour detection to the same guide rectangle the UI shows.
// This reduces false detections from the background.
const guide = computeGuideRect(src.cols, src.rows);

// Clamp to image bounds (safety)
const gx = Math.max(0, Math.min(guide.x, src.cols - 1));
const gy = Math.max(0, Math.min(guide.y, src.rows - 1));
const gw = Math.max(1, Math.min(guide.width, src.cols - gx));
const gh = Math.max(1, Math.min(guide.height, src.rows - gy));

const roiOffsetX = gx;
const roiOffsetY = gy;

const searchMat = src.roi(new cv.Rect(gx, gy, gw, gh));

    const imageArea = searchMat.rows * searchMat.cols;
    const minArea = imageArea * 0.02;
    const maxArea = imageArea * 0.9;

    const targetAspect = 1.6;

    const gray = new cv.Mat();
    cv.cvtColor(src, gray, cv.COLOR_RGBA2GRAY, 0);

    const blurred = new cv.Mat();
    cv.GaussianBlur(gray, blurred, new cv.Size(5, 5), 0);

    const edges = new cv.Mat();
    cv.Canny(blurred, edges, 50, 150);

    const contours = new cv.MatVector();
    const hierarchy = new cv.Mat();
    cv.findContours(edges, contours, hierarchy, cv.RETR_EXTERNAL, cv.CHAIN_APPROX_SIMPLE);

    let bestRect: { x: number; y: number; width: number; height: number } | null = null;
    let bestScore = 0;
    let bestQuad: { x: number; y: number }[] | null = null;

    for (let i = 0; i < contours.size(); i++) {
      const cnt = contours.get(i);

      const peri = cv.arcLength(cnt, true);
      const approx = new cv.Mat();
      cv.approxPolyDP(cnt, approx, 0.02 * peri, true);

      if (approx.rows !== 4) {
        approx.delete();
        cnt.delete();
        continue;
      }

      if (!cv.isContourConvex(approx)) {
        approx.delete();
        cnt.delete();
        continue;
      }

      const area = cv.contourArea(approx);
      if (area < minArea || area > maxArea) {
        approx.delete();
        cnt.delete();
        continue;
      }

      const pts: { x: number; y: number }[] = [];
      const data = approx.data32S;
      for (let j = 0; j < 8; j += 2) {
        pts.push({ x: data[j] + roiOffsetX, y: data[j + 1] + roiOffsetY });
      }

      const rect = cv.boundingRect(approx);
      const w = rect.width;
      const h = rect.height;

      const minWidth = searchMat.cols * 0.25;
      const minHeight = searchMat.rows * 0.18;
      if (w < minWidth || h < minHeight) {
        approx.delete();
        cnt.delete();
        continue;
      }

      const maxWidth = searchMat.cols * 0.95;
      const maxHeight = searchMat.rows * 0.95;
      if (w >= maxWidth && h >= maxHeight) {
        approx.delete();
        cnt.delete();
        continue;
      }

      const cx = rect.x + w / 2;
      const cy = rect.y + h / 2;
      const imgCx = searchMat.cols / 2;
      const imgCy = searchMat.rows / 2;

      const dx = (cx - imgCx) / searchMat.cols;
      const dy = (cy - imgCy) / searchMat.rows;
      const centerDist = Math.sqrt(dx * dx + dy * dy);

      if (centerDist > 0.35) {
        approx.delete();
        cnt.delete();
        continue;
      }

      const aspect = w > h ? w / h : h / w;
      if (aspect < 0.5 || aspect > 4.0) {
        approx.delete();
        cnt.delete();
        continue;
      }

      const aspectScore = 1 / (1 + Math.abs(aspect - targetAspect));
      const score = area * aspectScore;

      if (score > bestScore) {
        bestScore = score;
        bestRect = rect;
        bestQuad = pts;
      }

      approx.delete();
      cnt.delete();
    }

    gray.delete();
    blurred.delete();
    edges.delete();
    contours.delete();
    hierarchy.delete();
    searchMat.delete();

    if (bestRect) {
      const coverageW = bestRect.width / searchMat.cols;
      const coverageH = bestRect.height / searchMat.rows;

      if (coverageW < 0.5 || coverageH < 0.35) {
        bestRect = null;
        bestQuad = null;
      }
    }

    let cropRect = bestRect;
if (!cropRect) {
  const g = computeGuideRect(src.cols, src.rows);
  cropRect = { x: g.x, y: g.y, width: g.width, height: g.height };
}

    const dstWidth = 1000;
    const dstHeight = 600;
    const MIN_SHARPNESS = 3.5;

    if (bestQuad) {
      const quad = bestQuad.map((p) => ({
        x: Math.round(p.x / scale),
        y: Math.round(p.y / scale),
      }));

      const [tl, tr, br, bl] = orderQuadPoints(quad);

      const srcPts = cv.matFromArray(4, 1, cv.CV_32FC2, [
        tl.x, tl.y,
        tr.x, tr.y,
        br.x, br.y,
        bl.x, bl.y,
      ]);

      const dstPts = cv.matFromArray(4, 1, cv.CV_32FC2, [
        0, 0,
        dstWidth - 1, 0,
        dstWidth - 1, dstHeight - 1,
        0, dstHeight - 1,
      ]);

      const M = cv.getPerspectiveTransform(srcPts, dstPts);
      const warped = new cv.Mat();

      cv.warpPerspective(
        original,
        warped,
        M,
        new cv.Size(dstWidth, dstHeight),
        cv.INTER_LINEAR,
        cv.BORDER_REPLICATE
      );

      srcPts.delete();
      dstPts.delete();
      M.delete();

      const sharpness = computeSharpness(warped);

      if (!Number.isFinite(sharpness) || sharpness < MIN_SHARPNESS) {
        src.delete();
        original.delete();
        warped.delete();
        return { success: false, reason: "Card too blurry or invalid crop – using original image" };
      }

      const warpCanvas = document.createElement("canvas");
      warpCanvas.width = dstWidth;
      warpCanvas.height = dstHeight;
      cv.imshow(warpCanvas, warped);
      const croppedDataUrl = warpCanvas.toDataURL("image/png");

      src.delete();
      original.delete();
      warped.delete();

      return { success: true, dataUrl: croppedDataUrl, sharpness };
    }

    const rectX = Math.round(cropRect.x / scale);
    const rectY = Math.round(cropRect.y / scale);
    const rectW = Math.round(cropRect.width / scale);
    const rectH = Math.round(cropRect.height / scale);

    const x = Math.max(0, rectX);
    const y = Math.max(0, rectY);
    const w = Math.min(original.cols - x, rectW);
    const h = Math.min(original.rows - y, rectH);

    const cardRoi = original.roi(new cv.Rect(x, y, w, h));

    const dst = new cv.Mat();
    cv.resize(cardRoi, dst, new cv.Size(dstWidth, dstHeight), 0, 0, cv.INTER_AREA);

    const sharpness = computeSharpness(dst);

    if (!Number.isFinite(sharpness) || sharpness < MIN_SHARPNESS) {
      src.delete();
      original.delete();
      cardRoi.delete();
      dst.delete();
      return { success: false, reason: "Card too blurry or invalid crop – using original image" };
    }

    const warpCanvas = document.createElement("canvas");
    warpCanvas.width = dstWidth;
    warpCanvas.height = dstHeight;
    cv.imshow(warpCanvas, dst);
    const croppedDataUrl = warpCanvas.toDataURL("image/png");

    src.delete();
    original.delete();
    cardRoi.delete();
    dst.delete();

    return { success: true, dataUrl: croppedDataUrl, sharpness };
  } catch (err) {
    console.error("Error in cropIdCardFromDataUrl:", err);
    return { success: false, reason: "OpenCV error, using original image" };
  }
}
