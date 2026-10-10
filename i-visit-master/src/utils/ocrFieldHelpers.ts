// IMPORTANT: Used by ScanIdPage.tsx, _2ScanIdPage.tsx, and features/id/index.ts. ocrDataUrlViaHelper() returns a plain string here.
import { extractText } from "../api/Index";
import { getTemplateWithCustomRois } from "./cardTemplates";

import {
  parseTextByIdType,
  type ExtractedInfo as BaseExtractedInfo,
  normalizeDate,
  cleanNameCandidate,
} from "./idParsers";

export type IdFieldKey = "fullName" | "dob" | "idNumber" | "institution" | "faculty";

export interface FieldRoi {
  key: IdFieldKey;
  x: number;   // 0–1, relative to card width
  y: number;   // 0–1, relative to card height
  width: number;  // 0–1
  height: number; // 0–1
}

export async function cropFieldsFromCard(
  cardDataUrl: string,
  rois: FieldRoi[]
): Promise<Partial<Record<IdFieldKey, string>>> {
  if (!rois.length) return {};

  const img = new Image();
  img.src = cardDataUrl;

  await new Promise<void>((resolve, reject) => {
    img.onload = () => resolve();
    img.onerror = (e) => reject(e);
  });

  const baseCanvas = document.createElement("canvas");
  baseCanvas.width = img.width || 640;
  baseCanvas.height = img.height || 480;
  const baseCtx = baseCanvas.getContext("2d");
  if (!baseCtx) throw new Error("Failed to get 2D context");

  baseCtx.drawImage(img, 0, 0, baseCanvas.width, baseCanvas.height);

  const result: Partial<Record<IdFieldKey, string>> = {};

  for (const roi of rois) {
    const sx = roi.x * baseCanvas.width;
    const sy = roi.y * baseCanvas.height;
    const sw = roi.width * baseCanvas.width;
    const sh = roi.height * baseCanvas.height;

    const fieldCanvas = document.createElement("canvas");
    fieldCanvas.width = sw;
    fieldCanvas.height = sh;
    const fieldCtx = fieldCanvas.getContext("2d");
    if (!fieldCtx) continue;

    fieldCtx.drawImage(
      baseCanvas,
      sx,
      sy,
      sw,
      sh,
      0,
      0,
      sw,
      sh
    );

    const fieldDataUrl = fieldCanvas.toDataURL("image/png");
    result[roi.key] = fieldDataUrl;
  }

  return result;
}

export function extractNationalIdNumber(text: string): string {
  if (!text) return "";

  let cleaned = text
    .replace(/O/g, "0")
    .replace(/[Il]/g, "1")
    .replace(/\s+/g, " ")
    .trim();

  const cleaned12 = cleaned.replace(/PSN[-–\s]*/gi, '');
  const match12 = cleaned12.match(
    /(\d{4})[-–\s]*(\d{3,4})[-–\s]*(\d{4})[-–\s]*(\d{1,4})/
  );
  if (match12) {
    return `${match12[1]}-${match12[2]}-${match12[3]}-${match12[4]}`;
  }

  const match16 = cleaned.match(
    /\b\d{4}\s*-\s*\d{4}\s*-\s*\d{4}\s*-\s*\d{4}\b/
  );
  if (match16) {
    const digits = match16[0].replace(/\D+/g, "");
    if (digits.length === 16) {
      return [
        digits.slice(0, 4),
        digits.slice(4, 8),
        digits.slice(8, 12),
        digits.slice(12),
      ].join("-");
    }
  }

  return "";
}

export function extractDobFromText(text: string): string {
  if (!text) return "";

  const cleaned = text
    .replace(/[oO]/g, "0")
    .replace(/[իլI]/g, "1")
    .replace(/s/g, "5")
    .replace(/b/g, "8");

  return normalizeDate(cleaned) || "";
}

export function extractPhilHealthIdNumber(text: string): string {
  if (!text) return "";

  let cleaned = text
    .replace(/O/gi, "0")
    .replace(/[Il]/g, "1")
    .replace(/S/gi, "5")
    .replace(/B/gi, "8")
    .replace(/\s+/g, "")
    .trim();

  const match = cleaned.match(/(\d{2})\s*-?\s*(\d{9})\s*-?\s*(\d{1})/);
  if (match) {
    return `${match[1]}-${match[2]}-${match[3]}`;
  }

  const digitsOnly = cleaned.replace(/\D/g, "");
  if (digitsOnly.length >= 12) {
    const d = digitsOnly.slice(0, 12);
    return `${d.slice(0, 2)}-${d.slice(2, 11)}-${d.slice(11)}`;
  }

  return cleaned.replace(/[^\d-]/g, "");
}

export function extractSssIdNumber(text: string): string {
  if (!text) return "";

  let cleaned = text
    .replace(/O/gi, "0")
    .replace(/[Il]/g, "1")
    .replace(/S/gi, "5")
    .replace(/B/gi, "8")
    .replace(/\s+/g, "")
    .trim();

  const match = cleaned.match(/(\d{2})\s*-?\s*(\d{7})\s*-?\s*(\d{1})/);
  if (match) {
    return `${match[1]}-${match[2]}-${match[3]}`;
  }

  const digitsOnly = cleaned.replace(/\D/g, "");
  if (digitsOnly.length >= 10) {
    const d = digitsOnly.slice(0, 10);
    return `${d.slice(0, 2)}-${d.slice(2, 9)}-${d.slice(9)}`;
  }

  return cleaned.replace(/[^\d-]/g, "");
}

export function extractDriversLicenseIdNumber(text: string): string {
  if (!text) return "";

  const cleaned = text
    .toUpperCase()
    .replace(/[oO]/g, "0")
    .replace(/[Il]/g, "1")
    .replace(/\s+/g, "");

  // Match: LNN-NN-NNNNNN (allow missing hyphens/spaces)
  const m = cleaned.match(/\b([A-Z])(\d{2})[-–]?(\d{2})[-–]?(\d{6})\b/);
  if (!m) return "";

  return `${m[1]}${m[2]}-${m[3]}-${m[4]}`;
}

function decodeMrzYyMmDd(yyMMdd: string): string {
  const s = (yyMMdd || "").replace(/\D/g, "");
  if (s.length !== 6) return "";

  const yy = Number(s.slice(0, 2));
  const mm = Number(s.slice(2, 4));
  const dd = Number(s.slice(4, 6));
  if (!yy || mm < 1 || mm > 12 || dd < 1 || dd > 31) return "";

  // Century rule: MRZ dates are ambiguous; use "future => 1900s, else 2000s"
  // e.g. if today is 2026 and yy=75 => 1975, yy=04 => 2004
  const currentYY = new Date().getFullYear() % 100;
  const year = yy > currentYY ? 1900 + yy : 2000 + yy;

  return `${String(year).padStart(4, "0")}-${String(mm).padStart(2, "0")}-${String(dd).padStart(2, "0")}`;
}

export function extractPassportIdNumberFromMrzFragment(text: string): string {
  if (!text) return "";

  const upper = text.toUpperCase();

  // Tokenize to avoid MRZ noise glueing to the number
  const tokens = upper
    .replace(/[^A-Z0-9]/g, " ")
    .split(/\s+/)
    .filter(Boolean);

  // Prefer MRZ-ish pattern: (DOCNO 9 chars) + check digit + PHL
  // Example: A123456704PHL... => doc = A12345670(??) + check 4, but last char may be OCR-misread letter
  const compact = upper.replace(/\s+/g, "").replace(/</g, "");
  const mrz = compact.match(/([A-Z0-9]{9})(\d)PHL/);
  if (mrz) {
    let doc = mrz[1];

    // If last char is a digit, it often means OCR misread a trailing letter (C/O) as 0.
    // Safe generic fix: 0->O, 1->I, 2->Z, 5->S, 8->B (only at LAST char).
    const last = doc.slice(-1);
    if (/\d/.test(last)) {
      const map: Record<string, string> = { "0": "O", "1": "I", "2": "Z", "5": "S", "8": "B" };
      const fixed = map[last];
      if (fixed) doc = doc.slice(0, -1) + fixed;
    }

    // Enforce your expected schema: L + 7 digits + optional trailing letter (commonly letter-ending)
    if (/^[A-Z]\d{7}[A-Z]$/.test(doc)) return doc;

    // If it doesn't match strict schema, don't return a digit-ending doc.
    // This prevents things like LNNNNNNNO (OCR misread last letter) from being accepted.
    return "";
  }

  // Otherwise, scan tokens for DOCNO(+optional checkdigit) fragments like A1234567B8 or A123456708
  for (const t of tokens) {
    // Accept: A + 7 digits + [A-Z0-9] + optional check digit
    const m = t.match(/^([A-Z]\d{7}[A-Z0-9])(\d)?$/);
    if (!m) continue;

    let doc = m[1];

    // If doc ends with digit, try the same safe digit->letter repair at the end
    const last = doc.slice(-1);
    if (/\d/.test(last)) {
      const map: Record<string, string> = { "0": "O", "1": "I", "2": "Z", "5": "S", "8": "B" };
      const fixed = map[last];
      if (fixed) doc = doc.slice(0, -1) + fixed;
    }

    // Prefer strict schema if possible
    if (/^[A-Z]\d{7}[A-Z]$/.test(doc)) return doc;

    // Don't accept non-strict docs; better to return "" than a wrong-format number.
    continue;
  }

  return "";
}



export function extractPassportDobFromMrzFragment(text: string): string {
  if (!text) return "";

  const upper = text.toUpperCase().replace(/\s+/g, "");

  // Typical MRZ line2 has "...PHLYYMMDD..." (DOB right after nationality)
  const afterNat = upper.match(/PHL([0-9O]{6})/);
  if (afterNat) {
    const six = afterNat[1].replace(/O/g, "0");
    const iso = decodeMrzYyMmDd(six);
    if (iso) return iso;
  }

  // Fallback: any isolated YYMMDD in the fragment (still conservative)
  const anySix = upper.match(/\b([0-9O]{6})\b/);
  if (anySix) {
    const six = anySix[1].replace(/O/g, "0");
    const iso = decodeMrzYyMmDd(six);
    if (iso) return iso;
  }

  return "";
}



// general functions and stuff

export function correctOcrMistakes(text: string): string {
  if (!text) return "";

  let result = text;

  result = result.replace(/([A-Za-z])0([A-Za-z])/g, '$1O$2');
  result = result.replace(/([A-Za-z])1([A-Za-z])/g, '$1I$2');
  result = result.replace(/([A-Za-z])5([A-Za-z])/g, '$1S$2');
  result = result.replace(/([A-Za-z])8([A-Za-z])/g, '$1B$2');

  result = result.replace(/^0([A-Za-z])/g, 'O$1');
  result = result.replace(/([A-Za-z])0$/g, '$1O');
  result = result.replace(/^1([A-Za-z])/g, 'I$1');
  result = result.replace(/([A-Za-z])1$/g, '$1I');

  return result;
}

export function correctNameOcr(name: string): string {
  if (!name) return "";

  let result = name
    .replace(/0/g, 'O')
    .replace(/1/g, 'I')
    .replace(/5/g, 'S')
    .replace(/8/g, 'B');

  result = result
    .replace(/DE1A/gi, 'DELA')
    .replace(/DE L4/gi, 'DE LA')
    .replace(/D3/gi, 'DE')
    .replace(/CR[U0]Z/gi, 'CRUZ')
    .replace(/5ANT[O0]S/gi, 'SANTOS')
    .replace(/8A[U0]TISTA/gi, 'BAUTISTA')
    .replace(/R[E3]Y[E3]S/gi, 'REYES')
    .replace(/GARC1A/gi, 'GARCIA');

  return result;
}

export function cleanRoiName(text: string): string {
  if (!text) return "";

  // Drop line breaks & compress spaces
  let cleaned = text.replace(/\s+/g, " ").trim();

  // Remove obvious label words that your cards use
  cleaned = cleaned.replace(
    /(Apelyido|Last\s*Name|Mga\s*Pangalan|Given\s*Names|Gitnang\s*Apelyido|Middle\s*Name|Petsa\s*ng\s*Kapanganakan|Date\s*of\s*Birth)/gi,
    " "
  );

  // Remove obvious date-like fragments that leaked into the name ROI
  cleaned = cleaned.replace(
    /(January|February|March|April|May|June|July|August|September|October|November|December)\s+\d{1,2}\s+\d{4}/gi,
    " "
  );

  // Remove numeric date fragments that leaked into the name ROI (01/02/2000, 2000-01-02)
  cleaned = cleaned.replace(/\b\d{1,2}[\/\-]\d{1,2}[\/\-]\d{2,4}\b/g, " ");
  cleaned = cleaned.replace(/\b\d{4}[\/\-]\d{1,2}[\/\-]\d{1,2}\b/g, " ");

  // Remove common spillover field labels
  cleaned = cleaned.replace(/\b(SEX|GENDER|ADDRESS|NATIONALITY|CITIZENSHIP)\b/gi, " ");

  // Compress spaces again
  cleaned = cleaned.replace(/\s+/g, " ").trim();

  // Apply character correction for names (Sprint 03)
  cleaned = correctNameOcr(cleaned);

  return cleanNameCandidate(cleaned) || "";
}

export async function runWholeCardOcrAndParse(
  cardDataUrl: string,
  idType: string
): Promise<BaseExtractedInfo> {
  const blob = await (await fetch(cardDataUrl)).blob();
  const file = new File([blob], "full_card.png", { type: "image/png" });
  const json = await extractText(file);
  const extractedText: string = json.extractedText || "";
  return parseTextByIdType(extractedText, idType);
}

export async function ocrDataUrlViaHelper(dataUrl: string): Promise<string> {
  const responseBlob = await (await fetch(dataUrl)).blob();
  const file = new File([responseBlob], "field.png", { type: "image/png" });
  const json = await extractText(file); // uses your helper /api/ocr (Tess4J)
  return json.extractedText || "";
}

export function getRoisForIdType(idType: string): FieldRoi[] {
  const tpl = getTemplateWithCustomRois(idType);
  if (!tpl || !tpl.rois || tpl.rois.length === 0) return [];

  return tpl.rois.map((r) => ({
    key: r.key as IdFieldKey,
    x: r.x,
    y: r.y,
    width: r.width,
    height: r.height,
  }));
}

// ========== AI VISION OCR (OpenRouter via Helper) ==========

const HELPER_BASE_URL = import.meta.env.VITE_HELPER_BASE_URL ?? 'http://localhost:8765';

export interface VisionExtractResult {
  fullName: string;
  idNumber: string;
  dob: string;
  address: string;
  idType: string;
  gender: string;
  success: boolean;
}

export async function visionOcrExtract(
  dataUrl: string,
  expectedIdType?: string
): Promise<VisionExtractResult> {

  const emptyResult: VisionExtractResult = {
    fullName: "",
    idNumber: "",
    dob: "",
    address: "",
    idType: expectedIdType || "",
    gender: "",
    success: false,
  };

  try {
    const response = await fetch(dataUrl);
    const blob = await response.blob();
    const file = new File([blob], "id-card.png", { type: "image/png" });

    const formData = new FormData();
    formData.append("file", file);

const trimmedType = expectedIdType?.trim() || "";

const url =
  trimmedType && trimmedType !== "Auto"
    ? `${HELPER_BASE_URL}/api/ocr/ocrspace?expectedIdType=${encodeURIComponent(trimmedType)}`
    : `${HELPER_BASE_URL}/api/ocr/ocrspace`;

const ocrRes = await fetch(url, {
  method: "POST",
  body: formData,
});

    if (!ocrRes.ok) return emptyResult;

    const data = await ocrRes.json();

    if (!data?.success || !data?.fields) return emptyResult;

    return {
      fullName: data.fields.fullName || "",
      idNumber: data.fields.idNumber || "",
      dob: data.fields.dob || "",
      address: data.fields.address || "",
      idType: data.fields.idType || expectedIdType || "",
      gender: "",
      success: true,
    };

  } catch {
    return emptyResult;
  }
}
