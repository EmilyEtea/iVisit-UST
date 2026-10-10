// src/utils/cardTemplates.ts

// These are the ID types you already use in ScanIdPage / idParsers
export type SupportedIdType = "National ID" | "PhilHealth ID" | "UMID" | "School ID" | "SSS ID" | "Driver's License" | "Passport";

export type RoiKey = "fullName" | "dob" | "idNumber" | "institution" | "faculty";

export interface RoiSpec {
  key: RoiKey;
  label: string;

  // Normalized coordinates *inside the card* (0..1)
  // (0,0) = top-left of the yellow card guide, (1,1) = bottom-right.
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface CardTemplate {
  idType: SupportedIdType;
  displayName: string;
  rois: RoiSpec[];
}

/**
 * Rough templates based on typical layouts.
 * These don't have to be pixel-perfect; they are mainly
 * a visual guide for the guard *and* a shared config we
 * can later use for region-based OCR.
 */
const TEMPLATES: CardTemplate[] = [
  {
    idType: "National ID",
    displayName: "Philippine National ID",
    // Layout:
    // - ID Number: top-left corner (~15% from top)
    // - Photo: left side below ID number
    // - Last/Given/Middle Names: right side (40-90% width)
    // - DOB: bottom right
    rois: [
      {
        key: "idNumber",
        label: "ID Number",
        // "1234-5678-9012-1213" at top left
        x: 0,
        y: 0.26,
        width: 0.40,
        height: 0.13,
      },
      {
        key: "fullName",
        label: "Last / Given / Middle",
        // Name fields on right side (DELA CRUZ, JUAN MIGUEL..., VILLANUEVA)
        x: 0.40,
        y: 0.26,
        width: 0.55,
        height: 0.48,
      },
      {
        key: "dob",
        label: "Date of Birth",
        // "JANUARY 01, 1980" at bottom right
        x: 0.40,
        y: 0.74,
        width: 0.40,
        height: 0.12,
      },
    ],
  },
  {
    idType: "PhilHealth ID",
    displayName: "PhilHealth ID",
    // PhilHealth card layout (based on actual card positioning):
    // - Photo on left (~30% of card width)
    // - ID Number below header (~35% from top)
    // - Name below ID number (~43% from top)
    rois: [
      {
        key: "idNumber",
        label: "PhilHealth No.",
        // ID: "01-251761377-9" - positioned lower on card
        x: 0.30,
        y: 0.30,
        width: 0.50,
        height: 0.14,
      },
      {
        key: "fullName",
        label: "Full Name",
        // Name: "CABUG, JOHN AIM VREZYMIER TRAGO" - below ID
        x: 0.30,
        y: 0.40,
        width: 0.62,
        height: 0.12,
      },
    ],
  },
  {
    idType: "UMID",
    displayName: "UMID",
    rois: [
      {
        key: "fullName",
        label: "Full Name",
        x: 0.38,
        y: 0.35,
        width: 0.62,
        height: 0.36,
      },
      {
        key: "dob",
        label: "Date of Birth",
        x: 0.62,
        y: 0.675,
        width: 0.235,
        height: 0.12,
      },
      {
        key: "idNumber",
        label: "CRN / ID No.",
        x: 0.55,
        y: 0.23,
        width: 0.45,
        height: 0.18,
      },
    ],
  },
  // SSS ID Template
  {
    idType: "SSS ID",
    displayName: "SSS ID Card",
    rois: [
      {
        key: "fullName",
        label: "Full Name",
        // Name is centered, starts ~32% from top, spans ~12% height
        x: 0.25,
        y: 0.32,
        width: 0.70,
        height: 0.12,
      },
      {
        key: "idNumber",
        label: "SSS Number",
        // ID number is below name, starts ~45% from top
        x: 0.25,
        y: 0.45,
        width: 0.50,
        height: 0.12,
      },
    ],
  },
  // Driver's License Template (Philippine LTO)
  // Note: Many versions exist, but core fields are consistent
  {
    idType: "Driver's License",
    displayName: "Driver's License",
    rois: [
      {
        key: "fullName",
        label: "Full Name",
        // Name row: "DELA CRUZ, JUAN PEDRO GARCIA" at ~25% from top
        x: 0.32,
        y: 0.28,
        width: 0.65,
        height: 0.12,
      },
      {
        key: "dob",
        label: "Date of Birth",
        // DOB is in the row below name, ~32% from top
        x: 0.54,
        y: 0.38,
        width: 0.18,
        height: 0.12,
      },
      {
        key: "idNumber",
        label: "License No.",
        // License number is on left side, ~47% from top
        x: 0.30,
        y: 0.61,
        width: 0.28,
        height: 0.12,
      },
    ],
  },
  // Passport Template (biographic data page)
  // NOTE: Layout varies by country + passport version.
  // We're using the MRZ at the bottom line for cleaner reading.
  {
    idType: "Passport",
    displayName: "Passport",
    rois: [
      {
        key: "idNumber",
        label: "Passport No.",
        x: 0.05,
        y: 0.86,
        width: 0.25,
        height: 0.13  ,
      },
      {
        key: "fullName",
        label: "Full Name",
        x: 0.28,
        y: 0.18,
        width: 0.40,
        height: 0.30,
      },
      //{
      //  key: "fullName",
      //  label: "Full Name",
      //  // Name block (surname + given names)
      //  x: 0.05,
      //  y: 0.79,
      //  width: 0.58,
      //  height: 0.10,
      //},
      {
        key: "dob",
        label: "Date of Birth",
        // DOB line below the name block
        x: 0.31,
        y: 0.86,
        width: 0.19,
        height: 0.13,
      },
    ],
  },
];

/**
 * Get the template (if any) for the currently selected ID type.
 * Returns the default template from TEMPLATES array.
 */
export function getTemplateForIdType(idType: string | null | undefined): CardTemplate | null {
  if (!idType) return null;
  const tpl = TEMPLATES.find((t) => t.idType === idType);
  return tpl || null;
}

/**
 * Get template with custom ROIs merged if they exist.
 * Checks localStorage for user-customized ROIs first.
 */
export function getTemplateWithCustomRois(idType: string | null | undefined): CardTemplate | null {
  if (!idType) return null;

  const template = getTemplateForIdType(idType);
  if (!template) return null;

  const storageKey = `ivisit-custom-rois-${idType.replace(/\s+/g, '-').toLowerCase()}`;
  const stored = localStorage.getItem(storageKey);

  if (stored) {
    try {
      const custom = JSON.parse(stored);
      if (custom.rois && custom.rois.length > 0) {
        return { ...template, rois: custom.rois };
      }
    } catch {
      // Invalid JSON, use defaults
    }
  }

  return template;
}
