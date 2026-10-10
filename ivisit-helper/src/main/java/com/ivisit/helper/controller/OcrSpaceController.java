package com.ivisit.helper.controller;

import com.ivisit.helper.utils.ImagePreprocessor;
import com.ivisit.helper.utils.RoiTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OCR.space API controller for text extraction.
 * Acts as fallback when OpenRouter Vision fails.
 * API docs: https://ocr.space/ocrapi
 */
@RestController
@RequestMapping("/api/ocr")
public class OcrSpaceController {

    @Value("${ocrspace.api.key}")
    private String apiKey;

    @Value("${ocrspace.api.url}")
    private String apiUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    private static final Pattern PASSPORT_NO_RE = Pattern.compile("^[A-Z]{1,2}\\d{6,7}[A-Z]?$");
    private static final Pattern ISO_DOB_RE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    /**
     * Extract text using OCR.space API
     * - Always runs full-image OCR (keeps your original "working" behavior)
     * - Additionally runs ROI OCR when expectedIdType matches a template
     * - Merges ROI over full ONLY when ROI looks plausible for that field
     */
    @PostMapping("/ocrspace")
    public ResponseEntity<Map<String, Object>> extractWithOcrSpace(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "expectedIdType", required = false) String expectedIdType
    ) {
        if (file.isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, "Empty file");
        }

        try {
            String normalizedExpectedType = expectedIdType == null ? "" : expectedIdType.trim();
            boolean hasExpectedType = !normalizedExpectedType.isEmpty()
                    && !"Auto".equalsIgnoreCase(normalizedExpectedType);

            Map<String, RoiTemplate.Region> template =
                    hasExpectedType ? RoiTemplate.getTemplate(normalizedExpectedType) : null;

            // Compress image to under 1MB for OCR.space free tier
            byte[] imageBytes;
            try {
                imageBytes = compressImage(file.getBytes());
                System.out.println("[OCR.space] Image size: " + imageBytes.length / 1024 + " KB");
            } catch (Exception compressError) {
                System.err.println("[OCR.space] Compression failed, using original: " + compressError.getMessage());
                imageBytes = file.getBytes();
            }

            // 1) Always do FULL IMAGE OCR (this is the behavior that was "working" for you)
            String fullText = callOcrSpaceForText(imageBytes);

            // 2) Parse FULL fields from FULL text (your original logic, plus expected type lock)
            Map<String, String> fullFields = extractFieldsFromText(fullText, normalizedExpectedType);

            // 3) ROI OCR (optional)
            Map<String, String> roiFields = new HashMap<>();
            if (template != null) {
                BufferedImage original = ImageIO.read(new ByteArrayInputStream(file.getBytes()));
                if (original != null) {
                    for (Map.Entry<String, RoiTemplate.Region> entry : template.entrySet()) {
                        String fieldName = entry.getKey();
                        RoiTemplate.Region region = entry.getValue();

                        try {
                            BufferedImage crop = ImagePreprocessor.cropRegion(
                                    original,
                                    region.xPct, region.yPct,
                                    region.widthPct, region.heightPct
                            );

                            BufferedImage processed = ImagePreprocessor.preprocessCroppedRegion(crop);

                            byte[] roiBytes = toJpegBytes(processed);
                            roiBytes = compressImage(roiBytes);

                            String roiText = callOcrSpaceForText(roiBytes);
                            String normalized = roiText == null ? "" : normalizeField(fieldName, roiText);

                            if (!normalized.isEmpty()) {
                                if ("idNumber".equalsIgnoreCase(fieldName)) {
                                    if ("Passport".equalsIgnoreCase(normalizedExpectedType)) {
                                        String pass = normalizePassportIdNumberFromRoiText(normalized);
                                        if (!pass.isEmpty()) {
                                            normalized = pass;
                                        } else {
                                            String extracted = extractIdNumberFromBlob(normalized, normalizedExpectedType);
                                            if (extracted != null && !extracted.isEmpty()) normalized = extracted;
                                        }
                                    } else {
                                        String extracted = extractIdNumberFromBlob(normalized, normalizedExpectedType);
                                        if (extracted != null && !extracted.isEmpty()) normalized = extracted;
                                    }
                                } else if ("dob".equalsIgnoreCase(fieldName)) {
                                    if ("Passport".equalsIgnoreCase(normalizedExpectedType)) {
                                        String isoDob = normalizePassportDobFromRoiText(normalized);
                                        if (!isoDob.isEmpty()) {
                                            normalized = isoDob;
                                        } else {
                                            String extracted = extractDobFromBlob(normalized);
                                            if (extracted != null && !extracted.isEmpty()) normalized = extracted;
                                        }
                                    } else {
                                        String extracted = extractDobFromBlob(normalized);
                                        if (extracted != null && !extracted.isEmpty()) normalized = extracted;
                                    }
                                } else if ("fullName".equalsIgnoreCase(fieldName)) {
                                    normalized = cleanNameTail(normalized);
                                    String extracted = extractFullNameFromBlob(normalized, normalizedExpectedType);
                                    if (extracted != null && !extracted.isEmpty()) normalized = extracted;

                                    if ("UMID".equalsIgnoreCase(normalizedExpectedType)) {
                                        normalized = cleanUmidName(normalized);
                                    }
                                }
                            }

                            roiFields.put(fieldName, normalized);
                        } catch (Exception ignore) {
                            roiFields.put(fieldName, "");
                        }
                    }
                }
            }

            // 4) Merge: start from fullFields, overlay ROI only if plausible
            Map<String, String> merged = new HashMap<>(fullFields);

            for (Map.Entry<String, String> e : roiFields.entrySet()) {
                String key = e.getKey();
                String raw = e.getValue() == null ? "" : e.getValue().trim();
                if (raw.isEmpty()) continue;

                String cleaned = normalizeField(key, raw);

                // --- PASSPORT MRZ OVERRIDES ---
                if ("Passport".equalsIgnoreCase(normalizedExpectedType)) {

                    if ("idNumber".equalsIgnoreCase(key)) {
                        String current = merged.get("idNumber");
                        // If fullFields already produced a valid passport number, don't redo/override.
                        String cur = current == null ? "" : current.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
                        if (PASSPORT_NO_RE.matcher(cur).matches()) {
                            continue;
                        }

                        // Otherwise, let ROI try to supply it (MRZ fragment etc.)
                        String pass = normalizePassportIdNumberFromRoiText(raw);
                        if (pass != null && !pass.isEmpty()) {
                            merged.put("idNumber", pass);
                            continue;
                        }
                        // else fall through
                    }

                    if ("dob".equalsIgnoreCase(key)) {
                        String current = merged.get("dob");
                        // If fullFields already produced ISO DOB, don't redo/override.
                        String cur = current == null ? "" : current.trim();
                        if (ISO_DOB_RE.matcher(cur).matches()) {
                            continue;
                        }

                        String isoDob = normalizePassportDobFromRoiText(raw); // from your previous insert
                        if (isoDob != null && !isoDob.isEmpty()) {
                            merged.put("dob", isoDob);
                            continue;
                        }
                        // else fall through
                    }

                    //if ("fullName".equalsIgnoreCase(key)) {
                    //    // Only override from MRZ if the ROI text actually looks like MRZ text
                    //    if (raw.contains("P<") || raw.contains("<<")) {
                    //        String mrzName = extractPassportNameFromMrzLine1Loose(raw);
                    //        if (mrzName != null && !mrzName.isEmpty()) merged.put("fullName", mrzName);
                    //        continue;
                    //    }
                    //    // else: fall through to normal visual name cleaning
                    //}
                }
                // --- END PASSPORT OVERRIDES ---

                // idNumber: existing extractor (skip re-extract for Passport if it already looks valid)
                if ("idNumber".equalsIgnoreCase(key)) {
                    if ("Passport".equalsIgnoreCase(normalizedExpectedType)) {
                        String compact = cleaned.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
                        if (!compact.matches("^[A-Z]{1,2}\\d{6,7}[A-Z]?$")) {
                            String extracted = extractIdNumberFromBlob(cleaned, normalizedExpectedType);
                            if (!extracted.isEmpty()) cleaned = extracted;
                        } else {
                            cleaned = compact;
                        }
                    } else {
                        String extracted = extractIdNumberFromBlob(cleaned, normalizedExpectedType);
                        if (!extracted.isEmpty()) cleaned = extracted;
                    }
                }

                // dob: extract only the date substring (skip re-extract for Passport if ISO already)
                if ("dob".equalsIgnoreCase(key)) {
                    if ("Passport".equalsIgnoreCase(normalizedExpectedType)) {
                        if (!cleaned.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
                            String extracted = extractDobFromBlob(cleaned);
                            if (!extracted.isEmpty()) cleaned = extracted;
                            cleaned = sanitizeDob(cleaned);
                        }
                    } else {
                        String extracted = extractDobFromBlob(cleaned);
                        if (!extracted.isEmpty()) cleaned = extracted;
                        cleaned = sanitizeDob(cleaned);
                    }
                }

                if ("fullName".equalsIgnoreCase(key)) {
                    cleaned = cleanNameTail(cleaned);
                    String extracted = extractFullNameFromBlob(cleaned, normalizedExpectedType);
                    if (!extracted.isEmpty()) cleaned = extracted;
                }

                if ("Passport".equalsIgnoreCase(normalizedExpectedType) && "fullName".equalsIgnoreCase(key)) {
                    String v = cleaned.trim();
                    if (v.matches(".*\\d.*")) continue;         // no digits in names
                    if (v.contains("<<") || v.contains("P<")) continue; // avoid MRZ-ish noise
                    if (v.split("\\s+").length < 2) continue;   // still require 2 tokens
                }

                // Passport
                if ("Passport".equalsIgnoreCase(normalizedExpectedType) && "dob".equalsIgnoreCase(key)) {
                    // If ROI gave us a clean ISO DOB, always prefer it.
                    if (cleaned.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
                        merged.put(key, cleaned);
                        continue;
                    }
                }

                // UMID: prevent CRN region from poisoning fullName
                if ("UMID".equalsIgnoreCase(normalizedExpectedType) && "fullName".equalsIgnoreCase(key)) {
                    String idNum = merged.getOrDefault("idNumber", "");
                    String cName = cleaned.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
                    String cId = idNum.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);

                    boolean looksLikeCrn = cName.matches("^\\d{4}-\\d{7}-\\d$") || cName.matches("^CRN\\d{4}-\\d{7}-\\d$");

                    if (looksLikeCrn || (!cId.isEmpty() && (cName.equals(cId) || cName.contains(cId)))) {
                        continue; // ignore this ROI "fullName"
                    }
                }

                if ("Passport".equalsIgnoreCase(normalizedExpectedType)) {
                    String current = merged.getOrDefault(key, "").trim();
                    if (!current.isEmpty()) {

                        if ("idNumber".equalsIgnoreCase(key)) {
                            String cur = current.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
                            String nxt = cleaned.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);

                            boolean curOk = cur.matches("^[A-Z]{1,2}\\d{6,7}[A-Z]?$");
                            boolean nxtOk = nxt.matches("^[A-Z]{1,2}\\d{6,7}[A-Z]?$");

                            if (curOk && !nxtOk) continue; // don't let junk override a valid passport number
                        }

                        if ("dob".equalsIgnoreCase(key)) {
                            boolean curOk = current.matches("^\\d{4}-\\d{2}-\\d{2}$");
                            boolean nxtOk = cleaned.matches("^\\d{4}-\\d{2}-\\d{2}$");

                            if (curOk && !nxtOk) continue; // don't let non-ISO override ISO
                        }

                        if ("fullName".equalsIgnoreCase(key)) {
                            // Only override if at least 1 meaningful token is shared
                            String a = current.toUpperCase(Locale.ROOT);
                            String b = cleaned.toUpperCase(Locale.ROOT);
                            boolean sharesToken =
                                    Arrays.stream(b.split("\\s+"))
                                            .filter(t -> t.length() >= 3)
                                            .anyMatch(t -> a.contains(t));
                            if (!sharesToken) continue;
                        }
                    }
                }

                if (!isPlausibleField(key, cleaned, normalizedExpectedType)) continue;

                merged.put(key, cleaned);
            }

            // Response
            Map<String, Object> result = new HashMap<>();
            result.put("method", "ocrspace");
            result.put("success", true);
            result.put("extractedText", fullText != null ? fullText : "");
            result.put("fields", merged);

            result.put("roiTemplateUsed", template != null);
            result.put("expectedIdTypeReceived", expectedIdType);
            result.put("roiFields", roiFields);

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            System.err.println("[OCR.space] Exception: " + e.getMessage());
            e.printStackTrace();
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "OCR.space failed: " + e.getMessage());
        }
    }

    /**
     * Smart OCR endpoint - uses OCR.space for now
     */
    @PostMapping("/smart")
    public ResponseEntity<Map<String, Object>> smartOcr(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "expectedIdType", required = false) String expectedIdType
    ) {
        return extractWithOcrSpace(file, expectedIdType);
    }

    /**
     * Extract structured fields from OCR text
     * (This keeps your ORIGINAL behavior as much as possible, with optional expected-type lock)
     */
    private Map<String, String> extractFieldsFromText(String text, String expectedIdType) {
        Map<String, String> fields = new HashMap<>();
        if (text == null || text.isEmpty()) {
            return fields;
        }

        boolean lockType = expectedIdType != null
                && !expectedIdType.trim().isEmpty()
                && !"Auto".equalsIgnoreCase(expectedIdType.trim());

        String type = expectedIdType == null ? "" : expectedIdType.trim();

        if (lockType) {
            fields.put("idType", expectedIdType.trim());
        }

        String[] lines = text.split("\n");

        // PhilHealth ID pattern
        Pattern philhealthPattern = Pattern.compile("(\\d{2})[-\\s]?(\\d{8})[-\\s]?(\\d)");
        Matcher philhealthMatcher = philhealthPattern.matcher(text.replaceAll("\\s+", ""));
        if (philhealthMatcher.find() && (!lockType || "PhilHealth ID".equalsIgnoreCase(type))) {
            fields.put("idNumber", philhealthMatcher.group(1) + "-" +
                    philhealthMatcher.group(2) + "-" + philhealthMatcher.group(3));
            if (!lockType) fields.put("idType", "PhilHealth ID");
        }

        // SSS ID pattern
        Pattern sssPattern = Pattern.compile("(\\d{2})[-\\s]?(\\d{7})[-\\s]?(\\d)");
        Matcher sssMatcher = sssPattern.matcher(text.replaceAll("\\s+", ""));
        if (sssMatcher.find() && (!lockType || "SSS ID".equalsIgnoreCase(type)) && !fields.containsKey("idNumber")) {
            fields.put("idNumber", sssMatcher.group(1) + "-" +
                    sssMatcher.group(2) + "-" + sssMatcher.group(3));
            if (!lockType) fields.put("idType", "SSS ID");
        }

        // Driver's License number (LNN-NN-NNNNNN) – try to pick it up from full text too
        if (!fields.containsKey("idNumber")) {
            String dlClean = text.toUpperCase(Locale.ROOT)
                    .replaceAll("\\s+", "")
                    .replace('O', '0')
                    .replace('I', '1');
            Matcher dl = Pattern.compile("\\b([A-Z])(\\d{2})[-–]?(\\d{2})[-–]?(\\d{6})\\b").matcher(dlClean);
            if (dl.find()) {
                fields.put("idNumber", dl.group(1) + dl.group(2) + "-" + dl.group(3) + "-" + dl.group(4));
                if (!lockType) fields.put("idType", "Driver's License");
            }
        }

        // National ID patterns, tries PSN format first
        Pattern psnPattern = Pattern.compile(
                "(?:PSN[-–\\s]*)?([\\dO]{4})[-–\\s]?([\\dO]{3,4})[-–\\s]?([\\dO]{4})[-–\\s]?([\\dO]{1,4})");
        Matcher psnMatcher = psnPattern.matcher(text.replaceAll("[oO]", "0"));
        if (psnMatcher.find() && !fields.containsKey("idNumber")) {
            String g1 = psnMatcher.group(1).replaceAll("[oO]", "0");
            String g2 = psnMatcher.group(2).replaceAll("[oO]", "0");
            String g3 = psnMatcher.group(3).replaceAll("[oO]", "0");
            String g4 = psnMatcher.group(4).replaceAll("[oO]", "0");
            fields.put("idNumber", g1 + "-" + g2 + "-" + g3 + "-" + g4);
            if (!lockType) fields.put("idType", "National ID");
        }

        if (!fields.containsKey("idNumber")) {
            Pattern nationalIdPattern = Pattern.compile("(\\d{4})[-\\s]?(\\d{4})[-\\s]?(\\d{4})[-\\s]?(\\d{4})");
            Matcher nationalIdMatcher = nationalIdPattern.matcher(text);
            if (nationalIdMatcher.find()) {
                fields.put("idNumber", nationalIdMatcher.group(1) + "-" +
                        nationalIdMatcher.group(2) + "-" +
                        nationalIdMatcher.group(3) + "-" + nationalIdMatcher.group(4));
                if (!lockType) fields.put("idType", "National ID");
            }
        }

        String extractedName = extractFullNameFromBlob(text, type);
        if (extractedName != null && !extractedName.isEmpty()) {
            if ("National ID".equalsIgnoreCase(expectedIdType)) {
                extractedName = stripNationalIdLabelNoise(extractedName);
            }

            fields.put("fullName", extractedName);
        }

        // DOB (original: allows Month DD, YYYY)
        Pattern datePattern = Pattern.compile(
                "(\\d{4})[/-](\\d{2})[/-](\\d{2})|" +
                        "(\\d{2})[/-](\\d{2})[/-](\\d{4})|" +
                        "(JANUARY|FEBRUARY|MARCH|APRIL|MAY|JUNE|JULY|AUGUST|SEPTEMBER|OCTOBER|NOVEMBER|DECEMBER)\\s+(\\d{1,2})[.,]?\\s+(\\d{4})",
                Pattern.CASE_INSENSITIVE
        );
        String bestDob = extractBestDob(text);
        if (!bestDob.isEmpty()) {
            fields.put("dob", bestDob);
        }

        if (lockType) {
            // Force idNumber from the locked type (prevents auto patterns from hijacking)
            String forcedId = extractIdNumberFromBlob(text, type);
            if (forcedId != null && !forcedId.isEmpty()) {
                fields.put("idNumber", forcedId);
            }

            // Force name from locked-type extractor (prevents label soup / header soup)
            String forcedName = extractFullNameFromBlob(text, type);
            if (forcedName != null && !forcedName.isEmpty()) {
                fields.put("fullName", forcedName);
            }
        }

        if (lockType && "Driver's License".equalsIgnoreCase(type)) {
            String dlName = extractDriversLicenseNameFromText(text);
            if (dlName != null && !dlName.isEmpty()) {
                fields.put("fullName", dlName);
            }
        }

        // --- UMID name guard: prevent CRN from becoming fullName ---
        if ("UMID".equalsIgnoreCase(fields.get("idType"))) {

            String idNum = fields.getOrDefault("idNumber", "");
            String fullName = fields.getOrDefault("fullName", "");

            if (!idNum.isEmpty() && !fullName.isEmpty()) {

                String compactId = idNum.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
                String compactName = fullName.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);

                // If fullName equals or contains the CRN, it's wrong
                if (compactName.equals(compactId) || compactName.contains(compactId)) {

                    // remove bad name
                    fields.remove("fullName");

                    // re-extract from full OCR text (not ROI)
                    String recovered = extractFullNameFromBlob(text, "UMID");
                    // IMPORTANT: replace 'text' with your OCR-space text variable

                    if (recovered != null && !recovered.isEmpty()) {
                        fields.put("fullName", recovered);
                    }
                }
            }
        }

        if ("UMID".equalsIgnoreCase(fields.getOrDefault("idType", ""))) {
            String n = fields.getOrDefault("fullName", "");
            if (!n.isEmpty()) fields.put("fullName", cleanUmidName(n));
        }

        if ("PhilHealth ID".equalsIgnoreCase(type)) {
            String cleaned = cleanPhilHealthName(fields.get("fullName"));
            fields.put("fullName", cleaned);
        }


        if (lockType && "Passport".equalsIgnoreCase(type)) {
            // Force DOB from MRZ if present (prevents issue-date hijack)
            String mrzDob = extractPassportDobFromMrz(text);
            if (mrzDob != null && !mrzDob.isEmpty()) fields.put("dob", mrzDob);

            // Force passport number from MRZ if present (already trimmed in extractPassportNumberFromMrz)
            String mrzPass = extractPassportNumberFromMrz(text);
            if (mrzPass != null && !mrzPass.isEmpty()) fields.put("idNumber", mrzPass);

            // Prefer visual name for Passport (MRZ name is incomplete)
            String visualName = extractPassportNameFromVisualLines(text);
            if (visualName != null && !visualName.isEmpty()) fields.put("fullName", cleanNameTail(visualName));
        }

        return fields;
    }

    // ---------- ROI merge helpers ----------

    private String normalizeField(String key, String v) {
        String flat = v.replace("\r", "\n").replace("\n", " ").replaceAll("\\s+", " ").trim();

        if ("idNumber".equalsIgnoreCase(key)) {
            return flat.toUpperCase(Locale.ROOT);
        }
        if ("dob".equalsIgnoreCase(key)) {
            return flat.toUpperCase(Locale.ROOT);
        }
        if ("fullName".equalsIgnoreCase(key)) {
            return flat;
        }
        if ("address".equalsIgnoreCase(key)) {
            return flat;
        }
        return flat;
    }

    private boolean isPlausibleField(String key, String v, String expectedIdType) {
        String type = expectedIdType == null ? "" : expectedIdType.trim();

        if ("idNumber".equalsIgnoreCase(key)) {
            if ("National ID".equalsIgnoreCase(type)) {
                return Pattern.compile("\\b\\d{4}-\\d{4}-\\d{4}-\\d{4}\\b").matcher(v).find();
            }
            if ("Driver's License".equalsIgnoreCase(type)) {
                String s = v.replaceAll("\\s+", "");
                return Pattern.compile("[A-Z]\\d{2}-\\d{2}-\\d{6}").matcher(s).find()
                        || Pattern.compile("[A-Z]\\d{2}[-–]?\\d{2}[-–]?\\d{6}").matcher(s).find();
            }
            if ("PhilHealth ID".equalsIgnoreCase(type)) {
                return Pattern.compile("\\b\\d{2}-\\d{8}-\\d\\b").matcher(v.replaceAll("\\s+", "")).find();
            }
            if ("UMID".equalsIgnoreCase(type) || "UMID ID".equalsIgnoreCase(type)) {
                String s = v.replaceAll("\\s+", "");
                return Pattern.compile("\\b\\d{4}-\\d{7}-\\d\\b").matcher(s).find()
                        || Pattern.compile("\\b\\d{12}\\b").matcher(s).find();
            }
            if ("Passport".equalsIgnoreCase(type)) {
                String s = v.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
                return Pattern.compile("\\b([A-Z]\\d{6}|[A-Z]{2}\\d{6}|[A-Z]{2}\\d{7}|[A-Z]\\d{7}[A-Z])\\b").matcher(s).find();
            }
            return v.length() >= 6;
        }

        if ("dob".equalsIgnoreCase(key)) {
            // Accept either YYYY/MM/DD OR Month DD YYYY (because your older working flow used Month)
            boolean iso = Pattern.compile("\\b\\d{4}[/-]\\d{2}[/-]\\d{2}\\b").matcher(v).find();
            boolean month = Pattern.compile(
                    "\\b(JANUARY|FEBRUARY|MARCH|APRIL|MAY|JUNE|JULY|AUGUST|SEPTEMBER|OCTOBER|NOVEMBER|DECEMBER)\\s+\\d{1,2}[.,]?\\s+\\d{4}\\b",
                    Pattern.CASE_INSENSITIVE
            ).matcher(v).find();
            return iso || month;
        }

        if ("fullName".equalsIgnoreCase(key)) {
            String flat = v.replaceAll("\\s+", " ").trim();

            if (flat.length() < 5) return false;
            if (!flat.contains(" ")) return false;      // must have at least 2 tokens
            if (flat.matches(".*\\d.*")) return false;  // names should not contain digits
            if (!flat.matches("^[A-Za-z\\s,.'-]+$")) return false;

            String up = flat.toUpperCase(Locale.ROOT);
            if (up.contains(" CITY") || up.contains("NCR") || up.contains("DISTRICT") || up.contains("REGION")) {
                return false;
            }
            if (up.contains("APELYIDO") || up.contains("GIVEN") || up.contains("NAME")
                    || up.contains("PAGKAKAKILANLAN") || up.contains("PAMBANSANG")) {
                return false;
            }
            return true;
        }

        return true;
    }

    private String extractFullNameFromBlob(String text, String expectedIdType) {
        if (text == null) return "";
        String type = expectedIdType == null ? "" : expectedIdType.trim();

        String flat = text.replace('\r', '\n');

        // National ID: anchor-based extraction (prevents address/headers from becoming names)
        if ("National ID".equalsIgnoreCase(type)) {
            String anchored = extractNationalIdNameFromAnchors(flat);
            if (!anchored.isEmpty()) return cleanNameTail(anchored);
            // if anchors fail, fall back to generic logic below (but hardened)
        }

        // 1) Strong rule: if a "LAST, FIRST ..." exists anywhere, extract JUST that portion.
        Matcher commaName = Pattern.compile(
                "([A-Z][A-Z.'-]{1,}\\s*,\\s*[A-Z][A-Z\\s.'-]{2,})",
                Pattern.CASE_INSENSITIVE
        ).matcher(flat);
        while (commaName.find()) {
            String candidate = commaName.group(1).replaceAll("\\s+", " ").trim();
            if (looksLikeAddressLine(candidate)) continue;
            return cleanNameTail(candidate);
        }

        // 2) Otherwise: line-based filtering (hardened to reject address-ish lines too)
        String[] lines = flat.split("\n");
        List<String> keep = new ArrayList<>();

        for (String line : lines) {
            String s = line.replaceAll("\\s+", " ").trim();
            if (s.isEmpty()) continue;

            String up = s.toUpperCase(Locale.ROOT);

            // reject obvious boilerplate / labels
            if (up.contains("REPUB") || up.contains("PILIP") || up.contains("PHILIPP")
                    || up.contains("PAMBANSANG") || up.contains("PAGKAKAKILANLAN")
                    || up.contains("DEPARTMENT") || up.contains("TRANSPORTATION") || up.contains("OFFICE")
                    || up.contains("DRIVER") || up.contains("LICENSE")
                    || up.contains("APELY") || up.contains("PANGAL") || up.contains("GITNANG")
                    || up.contains("TIRAHAN") || up.contains("ADDRESS")
                    || up.contains("DATE") || up.contains("BIRTH")
                    || up.contains("IDENTI") || up.contains("CARD")
                    || up.equals("PHL") || up.equals("PHI")) {
                continue;
            }

            // reject digits
            if (s.matches(".*\\d.*")) continue;

            // reject address-ish lines (this is what stops "ST, GREENFIELDS" etc.)
            if (looksLikeAddressLine(s)) continue;

            // keep name-ish chars only
            if (!s.matches("^[A-Za-z\\s,.'-]+$")) continue;

            keep.add(s);
            if (keep.size() >= 4) break;
        }

        if ("UMID".equalsIgnoreCase(type) || "UMID ID".equalsIgnoreCase(type)) {
            // UMID fullName MUST be name text, never CRN.
            // Use anchor-based name extraction.
            String anchored = extractUmidNameFromAnchors(flat);
            if (!anchored.isEmpty()) return cleanNameTail(anchored);

            // Fallback: generic filtered lines (the keep[] logic below)
            // (do nothing here; let it fall through)
        }

        if ("Passport".equalsIgnoreCase(type)) {
            String visual = extractPassportNameFromVisualLines(flat);
            if (!visual.isEmpty()) return cleanNameTail(visual);

            // fallback only if visual failed
            String mrzName = extractPassportNameFromMrz(flat);
            if (!mrzName.isEmpty()) return cleanNameTail(mrzName);
        }


        if (keep.isEmpty()) return "";

        String joined = String.join(" ", keep)
                .replaceAll("\\s+", " ")
                .trim();

        return cleanNameTail(joined);
    }

    private String extractNationalIdNameFromAnchors(String flat) {
        String[] lines = flat.split("\n");

        String last = pickValueAfterAnchor(lines, "APEL", "LAST");
        String given = pickValueAfterAnchor(lines, "PANGAL", "GIVEN");
        String middle = pickValueAfterAnchor(lines, "GITNANG", "MIDDLE");

        // If at least LAST + GIVEN exist, consider it a success
        if (!last.isEmpty() && !given.isEmpty()) {
            String full = (last + " " + given + (middle.isEmpty() ? "" : " " + middle)).trim();
            return stripLabelNoise(full);
        }

        // fallback: sometimes labels are mangled, but the three name lines are still present
        // try: first 3 good "value-ish" lines after the ID number line
        int afterIdLine = indexAfterNationalIdNumberLine(lines);
        if (afterIdLine >= 0) {
            List<String> vals = new ArrayList<>();
            for (int i = afterIdLine; i < lines.length && vals.size() < 3; i++) {
                String v = normalizeCandidateValue(lines[i]);
                if (v.isEmpty()) continue;
                if (!isNameValueLine(v)) continue;
                vals.add(v);
            }
            if (vals.size() >= 2) {
                return stripLabelNoise(String.join(" ", vals));
            }
        }

        return "";
    }

    private int indexAfterNationalIdNumberLine(String[] lines) {
        Pattern psn = Pattern.compile("\\b\\d{4}[-\\s]?\\d{4}[-\\s]?\\d{4}[-\\s]?\\d{4}\\b");
        for (int i = 0; i < lines.length; i++) {
            if (psn.matcher(lines[i]).find()) return i + 1;
        }
        return -1;
    }

    private String pickValueAfterAnchor(String[] lines, String anchorPrefix, String englishFallback) {
        for (int i = 0; i < lines.length; i++) {
            String up = lines[i].toUpperCase(Locale.ROOT);

            // anchorPrefix catches OCR misspellings: APEL..., PANGAL..., GITNANG...
            if (up.contains(anchorPrefix) || up.contains(englishFallback)) {
                // pick the next *value* line
                for (int j = i + 1; j < Math.min(i + 4, lines.length); j++) {
                    String cand = normalizeCandidateValue(lines[j]);
                    if (cand.isEmpty()) continue;
                    if (!isNameValueLine(cand)) continue;
                    return cand;
                }
            }
        }
        return "";
    }

    private String normalizeCandidateValue(String s) {
        if (s == null) return "";
        return s.replaceAll("\\s+", " ").trim();
    }

    private boolean isNameValueLine(String s) {
        if (s == null) return false;
        String v = s.replaceAll("\\s+", " ").trim();
        if (v.isEmpty()) return false;

        // No digits, no obvious address markers
        if (v.matches(".*\\d.*")) return false;
        if (looksLikeAddressLine(v)) return false;

        // Avoid boilerplate
        String up = v.toUpperCase(Locale.ROOT);
        if (up.contains("REPUB") || up.contains("PILIP") || up.contains("PHILIPP")
                || up.contains("PAMBANSANG") || up.contains("PAGKAKAKILANLAN")
                || up.contains("IDENTI") || up.contains("CARD")
                || up.contains("TIRAHAN") || up.contains("ADDRESS")
                || up.contains("DATE") || up.contains("BIRTH")
                || up.equals("PHL") || up.equals("PHI")) return false;

        // keep name-ish chars only
        return v.matches("^[A-Za-z\\s,.'-]+$");
    }

    private String stripLabelNoise(String name) {
        if (name == null) return "";
        String s = name;

        // remove label fragments even when OCR misspells them (prefix-based)
        s = s.replaceAll("(?i)\\bAPEL\\w*\\b", "");
        s = s.replaceAll("(?i)\\bPANGAL\\w*\\b", "");
        s = s.replaceAll("(?i)\\bGITNANG\\w*\\b", "");
        s = s.replaceAll("(?i)\\bNAME\\b", "");
        s = s.replaceAll("(?i)\\bNOME\\b", "");
        s = s.replaceAll("(?i)\\bNOM\\b", "");

        return s.replaceAll("\\s{2,}", " ").trim();
    }

    private String extractDriversLicenseNameFromText(String text) {
        if (text == null) return "";

        String[] lines = text.replace("\r", "\n").split("\n");
        for (String line : lines) {
            String s = line.replaceAll("\\s+", " ").trim();
            if (s.isEmpty()) continue;

            // DL names are usually "LAST, FIRST MIDDLE"
            if (s.contains(",") && s.matches("^[A-Za-z\\s,.'-]+$")) {
                return cleanNameTail(s);
            }
        }
        return "";
    }

    private String extractDobFromBlob(String text) {
        if (text == null) return "";
        String t = text.toUpperCase(Locale.ROOT);

        Matcher iso = Pattern.compile("\\b\\d{4}[/-]\\d{2}[/-]\\d{2}\\b").matcher(t);
        if (iso.find()) return iso.group();

        Matcher dMonY = Pattern.compile(
                "\\b\\d{1,2}\\s+(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|SEPT|OCT|NOV|DEC)[.]?\\s+\\d{4}\\b",
                Pattern.CASE_INSENSITIVE
        ).matcher(t);

        if (dMonY.find()) return dMonY.group().replaceAll("\\s+", " ").trim();

        Matcher month = Pattern.compile(
                "\\b(JANUARY|FEBRUARY|MARCH|APRIL|MAY|JUNE|JULY|AUGUST|SEPTEMBER|OCTOBER|NOVEMBER|DECEMBER)\\s+\\d{1,2}[.]?[,]?\\s+\\d{4}\\b",
                Pattern.CASE_INSENSITIVE
        ).matcher(t);
        if (month.find()) return month.group().replaceAll("\\s+", " ").trim();

        return "";
    }

    private String extractIdNumberFromBlob(String text, String expectedIdType) {
        String type = expectedIdType == null ? "" : expectedIdType.trim();
        if (text == null) return "";

        String raw = text;
        String flat = raw.replaceAll("\\s+", "").toUpperCase(Locale.ROOT)
                .replace('O', '0')
                .replace('I', '1');

        if ("PhilHealth ID".equalsIgnoreCase(type)) {
            Matcher m = Pattern.compile("\\b\\d{2}-\\d{8}-\\d\\b").matcher(flat);
            if (m.find()) {
                return m.group();
            }

            // fallback: sometimes OCR drops hyphens
            Matcher digits = Pattern.compile("\\b\\d{11}\\b").matcher(flat);
            if (digits.find()) {
                String d = digits.group();
                return d.substring(0, 2) + "-" + d.substring(2, 10) + "-" + d.substring(10);
            }

            return "";
        }

        // National ID: ####-####-####-####
        if ("National ID".equalsIgnoreCase(type)) {
            Matcher m = Pattern.compile("\\b(\\d{4}-\\d{4}-\\d{4}-\\d{4})\\b").matcher(raw);
            return m.find() ? m.group(1) : "";
        }

        // Driver's License: A12-34-567890 (allow trailing expiry digits after)
        if ("Driver's License".equalsIgnoreCase(type)) {
            Matcher m = Pattern.compile("([A-Z])(\\d{2})[-–]?(\\d{2})[-–]?(\\d{6})").matcher(flat);
            if (!m.find()) return "";
            return m.group(1) + m.group(2) + "-" + m.group(3) + "-" + m.group(4);
        }

        // UMID / CRN: commonly 12 digits, sometimes formatted 4-7-1 (e.g., 0111-1234567-8)
        // (SSS docs show CRN like 0111-1234567-8) :contentReference[oaicite:1]{index=1}
        if ("UMID".equalsIgnoreCase(type) || "UMID ID".equalsIgnoreCase(type)) {
            // 1) Prefer CRN-anchored value (most reliable)
            Matcher crn = Pattern.compile("(?i)\\bCRN\\b\\s*[:\\-]?\\s*(\\d{4}[-–]?\\d{7}[-–]?\\d)\\b").matcher(text);
            if (crn.find()) {
                raw = crn.group(1).replaceAll("\\s+", "").replace('–', '-');
                Matcher fmt = Pattern.compile("(\\d{4})-?(\\d{7})-?(\\d)").matcher(raw);
                if (fmt.find()) return fmt.group(1) + "-" + fmt.group(2) + "-" + fmt.group(3);
            }

            // 2) Fallback: generic UMID pattern
            String compact = text.toUpperCase(Locale.ROOT).replaceAll("\\s+", "").replace('–', '-')
                    .replace('O', '0').replace('I', '1');

            Matcher fmt2 = Pattern.compile("\\b(\\d{4})-?(\\d{7})-?(\\d)\\b").matcher(compact);
            if (fmt2.find()) return "CRN-" + fmt2.group(1) + "-" + fmt2.group(2) + "-" + fmt2.group(3);

            Matcher digits12 = Pattern.compile("\\b\\d{12}\\b").matcher(compact);
            return digits12.find() ? digits12.group() : "";
        }

        // Passport (PH): patterns vary, but typical PH formats:
        // - 1 letter + 6 digits
        // - 2 letters + 6 digits
        // - 2 letters + 7 digits
        // - 1 letter + 7 digits + 1 letter
        // (Microsoft Purview definition) :contentReference[oaicite:2]{index=2}
        if ("Passport".equalsIgnoreCase(type)) {
            // 1) MRZ is best if present
            String passportNo = extractPassportNumberFromMrz(text);
            if (!passportNo.isEmpty()) return passportNo;

            // 2) Label-anchored (Passport No / Passport Number)
            String up = text.toUpperCase(Locale.ROOT).replace('\r', '\n');
            Matcher labeled = Pattern.compile(
                    "(?i)\\bPASSPORT\\b\\s*(?:NO|NUMBER|#)?\\s*[:\\-]?\\s*([A-Z0-9]{7,9})\\b"
            ).matcher(up.replaceAll("\\s+", " "));
            if (labeled.find()) {
                return labeled.group(1).trim();
            }

            // 3) Generic fallback: enforce PH passport format L + 7 digits + L (e.g., A1234567B)
            String compact = up.replaceAll("\\s+", "");
            Matcher p = Pattern.compile("\\b([A-Z]\\d{7}[A-Z])\\b").matcher(compact);
            if (p.find()) return p.group(1);

            // OCR sometimes prepends a junk letter (e.g., "ZA1234567B"). If we see 10 chars where dropping
            // the first yields a valid 9-char passport number, fix it.
            Matcher prefixed = Pattern.compile("\\b([A-Z])([A-Z]\\d{7}[A-Z])\\b").matcher(compact);
            if (prefixed.find()) return prefixed.group(2);

            return "";
        }

        return "";
    }

    private boolean looksLikeAddressLine(String s) {
        String up = s.toUpperCase(Locale.ROOT);

        return up.contains(" CITY")
                || up.contains("NCR")
                || up.contains("DISTRICT")
                || up.contains("REGION")
                || up.contains("BARANGAY")
                || up.contains("BRGY")
                || up.contains("SUBDIV")
                || up.contains("SUBDIVISION")
                || up.contains("ST ")
                || up.contains(" STREET")
                || up.contains("RD")
                || up.contains("ROAD")
                || up.contains("AVE")
                || up.contains("AVENUE")
                || up.contains("BLK")
                || up.contains("BLOCK")
                || up.contains("LOT");
    }

    private String stripNationalIdLabelNoise(String name) {
        if (name == null) return "";

        String cleaned = name;

        // Remove label fragments (prefix-based so OCR misspellings still match)
        cleaned = cleaned.replaceAll("(?i)\\bAPELY\\w*\\b", "");
        cleaned = cleaned.replaceAll("(?i)\\bPANGAL\\w*\\b", "");
        cleaned = cleaned.replaceAll("(?i)\\bGITNANG\\w*\\b", "");
        cleaned = cleaned.replaceAll("(?i)\\bNAME\\b", "");
        cleaned = cleaned.replaceAll("(?i)\\bNOME\\b", "");
        cleaned = cleaned.replaceAll("(?i)\\bNOM\\b", "");

        // Collapse whitespace
        cleaned = cleaned.replaceAll("\\s{2,}", " ").trim();

        return cleaned;
    }

    private String extractUmidNameFromAnchors(String flat) {
        String[] lines = flat.split("\n");

        String last = pickValueAfterAnyAnchor(lines, new String[]{"SURNAME", "SURN", "APELY"}, 4);
        String given = pickValueAfterAnyAnchor(lines, new String[]{"GIVEN", "GIV", "PANGAL"}, 4);
        String middle = pickValueAfterAnyAnchor(lines, new String[]{"MIDDLE", "MID", "GITNANG"}, 4);

        // If we got at least last + given, return SURNAME GIVEN MIDDLE
        if (!last.isEmpty() && !given.isEmpty()) {
            String full = (last + " " + given + (middle.isEmpty() ? "" : " " + middle)).trim();
            return stripLabelNoise(full);
        }

        // Fallback: pick up to 3 name-like lines after CRN line
        int afterCrn = indexAfterCrnLine(lines);
        if (afterCrn >= 0) {
            List<String> vals = new ArrayList<>();
            for (int i = afterCrn; i < lines.length && vals.size() < 3; i++) {
                String v = normalizeCandidateValue(lines[i]);
                if (v.isEmpty()) continue;
                if (!isNameValueLine(v)) continue;

                // avoid common UMID headers
                String up = v.toUpperCase(Locale.ROOT);
                if (up.contains("REPUBLIC") || up.contains("PHILIPPINES") || up.contains("UNIFIED") || up.contains("MULTI")
                        || up.equals("YILIP")) continue;

                vals.add(v);
            }
            if (vals.size() >= 2) return stripLabelNoise(String.join(" ", vals));
        }

        return "";
    }

    private int indexAfterCrnLine(String[] lines) {
        Pattern crn = Pattern.compile("(?i)\\bCRN\\b");
        for (int i = 0; i < lines.length; i++) {
            if (crn.matcher(lines[i]).find()) return i + 1;
        }
        return -1;
    }

    private String pickValueAfterAnyAnchor(String[] lines, String[] anchors, int lookahead) {
        for (int i = 0; i < lines.length; i++) {
            String up = lines[i].toUpperCase(Locale.ROOT);

            boolean hit = false;
            for (String a : anchors) {
                if (up.contains(a)) {
                    hit = true;
                    break;
                }
            }
            if (!hit) continue;

            for (int j = i + 1; j < Math.min(i + 1 + lookahead, lines.length); j++) {
                String cand = normalizeCandidateValue(lines[j]);
                if (cand.isEmpty()) continue;
                if (!isNameValueLine(cand)) continue;
                return cand;
            }
        }
        return "";
    }

    private String extractPassportNameFromVisualLines(String text) {
        if (text == null) return "";

        String[] lines = text.replace("\r", "\n").split("\n");

        // Passport number variants (loose on purpose):
        // A1234567B, AB1234567, A1234567, etc.
        Pattern passLine = Pattern.compile("(?i)\\b([A-Z]{1,2}\\d{6,7}[A-Z]?)\\b");

        // Stop when we hit DOB-ish or MRZ-ish or obvious non-name blocks
        Pattern stop = Pattern.compile("(?i)\\b(SEX|NATIONALITY|PLACE|BIRTH|DATE|ISSUE|EXPIR|AUTHORITY|DFA|PHL)\\b|<<");
        Pattern dobLike = Pattern.compile("(?i)\\b\\d{1,2}\\s+(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|SEPT|OCT|NOV|DEC)[A-Z]*\\s+\\d{2,4}\\b|\\b\\d{4}[/-]\\d{1,2}[/-]\\d{1,2}\\b");

        // 1) Try anchored labels first (if OCR catches them)
        for (int i = 0; i < lines.length; i++) {
            String up = lines[i].trim().toUpperCase(Locale.ROOT);

            if (up.contains("SURNAME") || up.contains("GIVEN") || up.contains("NAME")) {
                List<String> parts = new ArrayList<>();
                for (int j = i + 1; j < Math.min(i + 8, lines.length); j++) {
                    String s = lines[j].replaceAll("\\s+", " ").trim();
                    if (s.isEmpty()) continue;

                    if (stop.matcher(s).find() || dobLike.matcher(s).find()) break;

                    if (looksPassportNameish(s)) {
                        parts.add(s);
                        if (parts.size() >= 3) break;
                    }
                }
                String joined = String.join(" ", parts).replaceAll("\\s+", " ").trim();
                if (joined.length() >= 6) return joined;
            }
        }

        // 2) Fallback: find passport number line, then collect next name lines until DOB/stop
        int afterPass = -1;
        for (int i = 0; i < lines.length; i++) {
            String s = lines[i].replaceAll("\\s+", "").trim();
            if (s.isEmpty()) continue;
            if (passLine.matcher(s).find() && !s.contains("P<")) { // avoid MRZ line1
                afterPass = i + 1;
                break;
            }
        }

        if (afterPass < 0) return "";

        List<String> keep = new ArrayList<>();
        for (int i = afterPass; i < lines.length && keep.size() < 4; i++) {
            String s = lines[i].replaceAll("\\s+", " ").trim();
            if (s.isEmpty()) continue;

            // stop at DOB, MRZ, or labels/metadata
            if (stop.matcher(s).find() || dobLike.matcher(s).find()) break;

            // reject boilerplate quickly
            String up = s.toUpperCase(Locale.ROOT);
            if (up.contains("REPUB") || up.contains("PASSP") || up.contains("PALAPOR") || up.equals("PHL") || up.equals("PHE")) {
                continue;
            }

            if (looksPassportNameish(s)) {
                keep.add(s);
            }
        }

        String joined = String.join(" ", keep).replaceAll("\\s+", " ").trim();
        return joined.length() >= 6 ? joined : "";
    }

    private boolean looksPassportNameish(String s) {
        if (s == null) return false;

        String v = s.replaceAll("\\s+", " ").trim();
        if (v.length() < 2) return false;

        // no digits
        if (v.matches(".*\\d.*")) return false;

        // avoid MRZ-ish
        if (v.contains("<<")) return false;

        // allow unicode junk sometimes appears; keep it simple and accept only latin-ish punctuation
        if (!v.matches("^[A-Za-z\\s,.'-]+$")) return false;

        // reject common non-name tokens
        String up = v.toUpperCase(Locale.ROOT);
        if (up.contains("REPUBLIC") || up.contains("PHILIPP") || up.contains("PASSPORT") || up.contains("PALAPORT"))
            return false;

        return true;
    }

    private String extractPassportNameFromMrz(String flat) {
        String[] lines = flat.toUpperCase(Locale.ROOT).replace("\r", "\n").split("\n");

        // Find a line that starts with P< (MRZ line 1)
        for (String line : lines) {
            String s = line.replaceAll("\\s+", "").trim();
            if (!s.startsWith("P<")) continue;

            // MRZ name field after country: P<PHL<SURNAME<<GIVEN<NAMES...
            int idx = s.indexOf("<<");
            if (idx < 0) continue;

            // remove leading "P<XXX"
            String after = s.length() >= 5 ? s.substring(5) : s.substring(2); // tolerate OCR truncation
            // split surname and given
            String[] parts = after.split("<<", 2);
            if (parts.length < 2) continue;

            String surname = parts[0].replace("<", " ").trim();
            String given = parts[1].replace("<", " ").trim();

            String full = (surname + " " + given).replaceAll("\\s+", " ").trim();
            // avoid empty/garbage
            if (full.length() >= 6) return full;
        }
        return "";
    }

    private String extractPassportNumberFromMrzLine2Fragment(String text) {
        if (text == null) return "";
        String[] lines = text.toUpperCase(Locale.ROOT).replace("\r", "\n").split("\n");

        for (String line : lines) {
            String s = line.replaceAll("\\s+", "").trim();
            if (s.length() < 20) continue;

            Matcher m = Pattern.compile("^([A-Z0-9<]{9})[0-9<][A-Z]{3}\\d{6}[0-9<].*").matcher(s);
            if (!m.find()) continue;

            String pass = m.group(1).replace("<", "").trim();
            return pass.length() >= 6 ? pass : "";
        }
        return "";
    }

    private String extractDobFromMrzLine2Fragment(String text) {
        if (text == null) return "";
        String[] lines = text.toUpperCase(Locale.ROOT).replace("\r", "\n").split("\n");

        for (String line : lines) {
            String s = line.replaceAll("\\s+", "").trim();
            if (s.length() < 25) continue;

            Matcher m = Pattern.compile("^[A-Z0-9<]{9}[0-9<][A-Z]{3}(\\d{6})[0-9<].*").matcher(s);
            if (!m.find()) continue;

            String yymmdd = m.group(1);
            int yy = Integer.parseInt(yymmdd.substring(0, 2));
            int mm = Integer.parseInt(yymmdd.substring(2, 4));
            int dd = Integer.parseInt(yymmdd.substring(4, 6));

            int currentYY = java.time.LocalDate.now().getYear() % 100;
            int fullYear = (yy > currentYY ? 1900 + yy : 2000 + yy);

            return validateAndFormatDob(fullYear, mm, dd);
        }
        return "";
    }

    private String extractPassportNameFromMrzLine1Loose(String text) {
        if (text == null) return "";
        String s = text.toUpperCase(Locale.ROOT)
                .replace("\r", "\n")
                .replaceAll("\\s+", "")
                // OCR.space often returns K where MRZ has <
                .replace('K', '<');

        // We want something that contains the "P<" start OR at least "<<"
        // Example: P<PHLETEA<<EMILY<<<<<<<<<<
        int idx = s.indexOf("P<");
        if (idx >= 0) s = s.substring(idx);

        // Remove all chars except A-Z and <
        s = s.replaceAll("[^A-Z<]", "");
        if (!s.contains("<<")) return "";

        // MRZ line 1: P<ISO + SURNAME<<GIVEN<NAMES<<<
        // Split on "<<": left part contains prefix+surname, right part is given names with < separators
        String[] parts = s.split("<<", 2);
        if (parts.length < 2) return "";

        String left = parts[0];   // e.g. P<PHLETEA
        String right = parts[1];  // e.g. EMILY<<<<<<<<

        // surname is after "P<" + 3 letters
        left = left.replaceFirst("^P<[A-Z]{3}", ""); // remove P<PHL
        String surname = left.replace("<", "").trim();
        if (surname.isEmpty()) return "";

        // given names are before the first "<<<" and with < separators
        String given = right.split("<<<", 2)[0].replace("<", " ").replaceAll("\\s+", " ").trim();
        if (given.isEmpty()) return "";

        return (surname + " " + given).replaceAll("\\s+", " ").trim();
    }

    private String extractPassportNumberFromMrz(String text) {
        if (text == null) return "";
        String[] lines = text.toUpperCase(Locale.ROOT).replace("\r", "\n").split("\n");

        // MRZ is two lines; second line begins with passport number (9 chars) + check digit
        // We’ll find a P< line and then use the next non-empty line as MRZ line 2.
        for (int i = 0; i < lines.length; i++) {
            String l1 = lines[i].replaceAll("\\s+", "").trim();
            if (!l1.contains("P<")) continue;

            for (int j = i + 1; j < lines.length; j++) {
                String l2 = lines[j].replaceAll("\\s+", "").trim();
                if (l2.isEmpty()) continue;
                if (l2.length() < 20) continue;

                String passRaw = l2.substring(0, Math.min(10, l2.length())).replace("<", "").trim();
// take 10 to include the check digit if present (doc(9) + check(1))

                String pass = passRaw.replaceAll("[^A-Z0-9]", "").trim();
                if (pass.length() < 6) break;

                // If it's MRZ-like: DOCNO(9) + CHECK(1)
                // Example: LNNNNNNNL(9) + N(check)
                if (pass.length() >= 10 && Character.isDigit(pass.charAt(9))) {
                    String docNo = pass.substring(0, 9);
                    // sanity: docNo looks like a passport number
                    if (docNo.matches("[A-Z]{1,2}\\d{6,7}[A-Z]?")) {
                        return docNo;
                    }
                }

                // If we have at least 9 chars, treat the first 9 as DOCNO and NEVER return the check digit.
                if (pass.length() >= 9) {
                    String docNo = pass.substring(0, 9);

                    // If OCR misread the trailing letter as a digit, repair only the LAST char conservatively.
                    char last = docNo.charAt(8);
                    if (Character.isDigit(last)) {
                        char fixed = last;
                        if (last == '0') fixed = 'O';
                        else if (last == '1') fixed = 'I';
                        else if (last == '2') fixed = 'Z';
                        else if (last == '5') fixed = 'S';
                        else if (last == '8') fixed = 'B';
                        docNo = docNo.substring(0, 8) + fixed;
                    }

                    // Strict Passport format you want: L + 7 digits + L (9 chars total)
                    if (docNo.matches("^[A-Z]\\d{7}[A-Z]$")) {
                        return docNo;
                    }

                    // If it doesn't match strict, don't output a wrong number.
                    // Returning "" forces fallback to ROI fragment parsing (which you already patched).
                    return "";
                }

                return "";
            }
        }
        return "";
    }

    private String extractPassportDobFromMrz(String text) {
        if (text == null) return "";

        String[] lines = text.toUpperCase(Locale.ROOT).replace("\r", "\n").split("\n");

        for (int i = 0; i < lines.length; i++) {
            String l1 = lines[i].replaceAll("\\s+", "").trim();
            if (!l1.contains("P<")) continue;

            // find MRZ line 2
            String l2 = "";
            for (int j = i + 1; j < lines.length; j++) {
                String cand = lines[j].replaceAll("\\s+", "").trim();
                if (cand.isEmpty()) continue;
                l2 = cand;
                break;
            }
            if (l2.isEmpty()) continue;

            // Need at least up to birthdate field: indexes 13..18 (YYMMDD)
            // Format: doc(9) + check(1) + nationality(3) + birth(6) ...
            if (l2.length() < 19) continue;

            String yymmdd = l2.substring(13, 19).replaceAll("[^0-9]", "");
            if (yymmdd.length() != 6) continue;

            int yy = safeInt(yymmdd.substring(0, 2));
            int mm = safeInt(yymmdd.substring(2, 4));
            int dd = safeInt(yymmdd.substring(4, 6));

            if (yy < 0 || mm < 1 || mm > 12 || dd < 1 || dd > 31) continue;

            int currentYY = Calendar.getInstance().get(Calendar.YEAR) % 100;
            int year = (yy > currentYY ? 1900 + yy : 2000 + yy);

            String iso = validateAndFormatDob(year, mm, dd);
            if (!iso.isEmpty()) return iso;
        }

        return "";
    }

    private String extractDobFromMrzLoose(String text) {
        if (text == null) return "";
        // normalize obvious OCR confusions
        String s = text.toUpperCase(Locale.ROOT)
                .replace("\r", "\n")
                .replaceAll("\\s+", "")
                .replace('O', '0')
                .replace('I', '1');

        // Find any 6 consecutive digits and try YYMMDD decode
        Matcher m = Pattern.compile("(\\d{6})").matcher(s);
        while (m.find()) {
            String yymmdd = m.group(1);

            int yy = safeInt(yymmdd.substring(0, 2));
            int mm = safeInt(yymmdd.substring(2, 4));
            int dd = safeInt(yymmdd.substring(4, 6));
            if (yy < 0 || mm < 0 || dd < 0) continue;

            int currentYY = java.time.LocalDate.now().getYear() % 100;
            int year = (yy > currentYY ? 1900 + yy : 2000 + yy);

            String iso = validateAndFormatDob(year, mm, dd);
            if (!iso.isEmpty()) return iso;
        }
        return "";
    }

    private String extractPassportNumberLoose(String s) {
        if (s == null) return "";
        String v = s.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");

        // Find a token that starts with a letter and has enough length to be a passport number
        Matcher m = Pattern.compile("([A-Z][A-Z0-9]{7,12})").matcher(v);
        if (!m.find()) return "";

        String token = m.group(1);

        // If OCR glued extra chars (like the MRZ check digit + junk), keep the first 9.
        if (token.length() > 9) token = token.substring(0, 9);

        // Final sanity: require at least 6 chars
        return token.length() >= 6 ? token : "";
    }

    private String normalizePassportIdNumberFromRoiText(String s) {
        if (s == null) return "";

        // 1) If ROI accidentally includes enough of MRZ line 2, parse it properly
        String mrz = extractPassportNumberFromMrzLine2Fragment(s);
        if (mrz != null && !mrz.isEmpty()) return mrz;

        // 2) Common ROI fragment case: "A1234567B" (doc + check digit)
        String stripped = stripPassportMrzCheckDigitIfPresent(s);
        if (stripped != null && !stripped.isEmpty()) {
            String v = stripped.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
            if (v.matches("^[A-Z]\\d{7}[A-Z]$")) return v;
            if (v.matches("^[A-Z][A-Z]\\d{7}[A-Z]$") && v.substring(1).matches("^[A-Z]\\d{7}[A-Z]$")) return v.substring(1);
        }

        // 3) Loose tokenization (in case OCR produced extra glue)
        String loose = extractPassportNumberLoose(s);
        if (loose != null && !loose.isEmpty()) {
            String v = stripPassportMrzCheckDigitIfPresent(loose).replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
            if (v.matches("^[A-Z]\\d{7}[A-Z]$")) return v;
            if (v.matches("^[A-Z][A-Z]\\d{7}[A-Z]$") && v.substring(1).matches("^[A-Z]\\d{7}[A-Z]$")) return v.substring(1);
        }

        return "";
    }

    private String normalizePassportDobFromRoiText(String s) {
        if (s == null) return "";

        // 1) If ROI contains enough of MRZ line 2, parse DOB from it
        String mrz = extractDobFromMrzLine2Fragment(s);
        if (mrz != null && !mrz.isEmpty()) return mrz;

        // 2) Loose MRZ-ish scanning (handles fragments / OCR weirdness)
        mrz = extractDobFromMrzLoose(s);
        if (mrz != null && !mrz.isEmpty()) return mrz;

        return "";
    }

    private String stripPassportMrzCheckDigitIfPresent(String s) {
        if (s == null) return "";
        String v = s.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        // common case from MRZ: passportNo + 1 check digit (your sample: A1234567BW)
        if (v.matches("^[A-Z][A-Z0-9]{7,9}\\d$")) return v.substring(0, v.length() - 1);
        return v;
    }

    private String cleanUmidName(String s) {
        if (s == null) return "";
        String out = s.toUpperCase(Locale.ROOT)
                .replace('\r', '\n')
                .replaceAll("[^A-Z\\s,'\\-]", " ")   // drop digits and weird symbols
                .replaceAll("\\s+", " ")
                .trim();

        // Remove UMID label-ish tokens that OCR often mangles
        out = out.replaceAll("\\b(SURNAME|SURN|SURNA\\w*|SU\\w{3,})\\b", " ");
        out = out.replaceAll("\\b(GIVEN|GIV\\w*|GIVEN\\w*|GIVEY|GIVENNA\\w*)\\b", " ");
        out = out.replaceAll("\\b(MIDDLE|MID\\w*|MIDDL\\w*|ADOLE\\w*|NALE)\\b", " ");
        out = out.replaceAll("\\b(NAME)\\b", " ");
        out = out.replaceAll("\\b(CRN)\\b", " ");

        // Collapse spaces again
        out = out.replaceAll("\\s+", " ").trim();

        // Keep only 2–4 words max (UMID name fields are short)
        String[] parts = out.split(" ");
        List<String> kept = new ArrayList<>();
        for (String p : parts) {
            if (p.length() < 2) continue;
            if (p.length() > 20) continue;
            kept.add(p);
            if (kept.size() >= 4) break;
        }
        return String.join(" ", kept).trim();
    }

    private String cleanPhilHealthName(String s) {
        if (s == null) return "";

        String out = s.toUpperCase(Locale.ROOT);

        // Remove month words
        out = out.replaceAll("\\b(JANUARY|FEBRUARY|MARCH|APRIL|MAY|JUNE|JULY|AUGUST|SEPTEMBER|OCTOBER|NOVEMBER|DECEMBER)\\b", "");

        // Remove gender markers
        out = out.replaceAll("\\b(MALE|FEMALE)\\b", "");

        // Remove years accidentally attached
        out = out.replaceAll("\\b\\d{4}\\b", "");

        out = out.replaceAll("\\s+", " ").trim();

        return out;
    }


    private String sanitizeDob(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toUpperCase(Locale.ROOT);

        // Normalize whitespace
        s = s.replaceAll("\\s+", " ");

        Matcher dMonY = Pattern.compile(
                "\\b(\\d{1,2})\\s+(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|SEPT|OCT|NOV|DEC)[.]?\\s+(\\d{4})\\b",
                Pattern.CASE_INSENSITIVE
        ).matcher(s);

        if (dMonY.find()) {
            int dd = safeInt(dMonY.group(1));
            int mm = monthToNumber(dMonY.group(2));
            int yy = safeInt(dMonY.group(3));
            return validateAndFormatDob(yy, mm, dd);
        }

        Matcher monDY = Pattern.compile(
                "\\b(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|SEPT|OCT|NOV|DEC)[.]?\\s+(\\d{1,2})[.,]?\\s+(\\d{4})\\b",
                Pattern.CASE_INSENSITIVE
        ).matcher(s);

        if (monDY.find()) {
            int mm = monthToNumber(monDY.group(1));
            int dd = safeInt(monDY.group(2));
            int yy = safeInt(monDY.group(3));
            return validateAndFormatDob(yy, mm, dd);
        }

        // 1) Month name format: "JANUARY 01 2002" / "JANUARY 1, 2002"
        Matcher month = Pattern.compile(
                "\\b(JANUARY|FEBRUARY|MARCH|APRIL|MAY|JUNE|JULY|AUGUST|SEPTEMBER|OCTOBER|NOVEMBER|DECEMBER)\\s+(\\d{1,2})[.,]?\\s+(\\d{4})\\b",
                Pattern.CASE_INSENSITIVE
        ).matcher(s);

        if (month.find()) {
            int mm = monthToNumber(month.group(1));
            int dd = safeInt(month.group(2));
            int yy = safeInt(month.group(3));
            return validateAndFormatDob(yy, mm, dd);
        }

        // Replace / with -
        s = s.replace('/', '-');

        // 2) ISO: YYYY-MM-DD
        Matcher iso = Pattern.compile("\\b(\\d{4})-(\\d{1,2})-(\\d{1,2})\\b").matcher(s);
        if (iso.find()) {
            int yy = safeInt(iso.group(1));
            int mm = safeInt(iso.group(2));
            int dd = safeInt(iso.group(3));
            return validateAndFormatDob(yy, mm, dd);
        }

        // 3) DMY/MDY: DD-MM-YYYY or MM-MM-YYYY (ambiguous)
        Matcher dmy = Pattern.compile("\\b(\\d{1,2})-(\\d{1,2})-(\\d{4})\\b").matcher(s);
        if (dmy.find()) {
            int a = safeInt(dmy.group(1));
            int b = safeInt(dmy.group(2));
            int yy = safeInt(dmy.group(3));

            // Try DD-MM-YYYY first
            String v1 = validateAndFormatDob(yy, b, a);
            if (!v1.isEmpty()) return v1;

            // Then try MM-DD-YYYY
            return validateAndFormatDob(yy, a, b);
        }

        return "";
    }

    private int safeInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return -1;
        }
    }

    private int monthToNumber(String month) {
        String m = month == null ? "" : month.toUpperCase(Locale.ROOT).trim();

        switch (m) {
            case "JANUARY":
            case "JAN":
                return 1;

            case "FEBRUARY":
            case "FEB":
                return 2;

            case "MARCH":
            case "MAR":
                return 3;

            case "APRIL":
            case "APR":
                return 4;

            case "MAY":
                return 5;

            case "JUNE":
            case "JUN":
                return 6;

            case "JULY":
            case "JUL":
                return 7;

            case "AUGUST":
            case "AUG":
                return 8;

            case "SEPTEMBER":
            case "SEPT":
            case "SEP":
                return 9;

            case "OCTOBER":
            case "OCT":
                return 10;

            case "NOVEMBER":
            case "NOV":
                return 11;

            case "DECEMBER":
            case "DEC":
                return 12;

            default:
                return -1;
        }
    }

    private String validateAndFormatDob(int year, int month, int day) {
        if (year <= 0 || month <= 0 || day <= 0) return "";

        int currentYear = Calendar.getInstance().get(Calendar.YEAR);

        // Loosen a bit vs 1900, but still sane for IDs:
        if (year < 1850 || year > currentYear) return "";

        if (month < 1 || month > 12) return "";
        if (day < 1 || day > 31) return "";

        try {
            Calendar cal = Calendar.getInstance();
            cal.setLenient(false);
            cal.set(year, month - 1, day);
            cal.getTime();
        } catch (Exception e) {
            return "";
        }

        return String.format("%04d-%02d-%02d", year, month, day);
    }

    private String extractBestDob(String text) {
        if (text == null || text.isEmpty()) return "";

        // Collect multiple date styles; we’ll score/filter them.
        Pattern p = Pattern.compile(
                "\\b(\\d{4})[/-](\\d{1,2})[/-](\\d{1,2})\\b" +
                        "|\\b(\\d{1,2})[/-](\\d{1,2})[/-](\\d{4})\\b" +
                        "|\\b(JANUARY|FEBRUARY|MARCH|APRIL|MAY|JUNE|JULY|AUGUST|SEPTEMBER|OCTOBER|NOVEMBER|DECEMBER)\\s+(\\d{1,2})[.,]?\\s+(\\d{4})\\b" +
                        "|\\b(\\d{1,2})\\s+(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|SEPT|OCT|NOV|DEC)[.]?\\s+(\\d{4})\\b",
                Pattern.CASE_INSENSITIVE
        );

        Matcher m = p.matcher(text.replace('\r', '\n'));
        String best = "";
        int bestScore = -1;

        while (m.find()) {
            String raw = m.group();
            String clean = sanitizeDob(raw);
            if (clean.isEmpty()) continue;

            int score = scoreDob(clean, text, m.start());
            if (score > bestScore) {
                bestScore = score;
                best = clean;
            }
        }

        return best;
    }

    private int scoreDob(String isoDob, String fullText, int matchIndex) {
        int score = 0;

        // Prefer nearer the top? Not always, but mild preference:
        if (matchIndex < Math.max(0, fullText.length() * 0.6)) score += 1;

        // Prefer dates near keywords (DOB contexts)
        String window = fullText.substring(Math.max(0, matchIndex - 40), Math.min(fullText.length(), matchIndex + 40))
                .toUpperCase(Locale.ROOT);

        if (window.contains("BIRTH") || window.contains("DOB") || window.contains("DATE OF")) score += 5;

        // Penalize expiry-ish contexts
        if (window.contains("EXP") || window.contains("EXPIR") || window.contains("VALID")) score -= 3;

        // Prefer reasonable adult years slightly (optional, helps reduce “2072” if it slips through elsewhere)
        try {
            int year = Integer.parseInt(isoDob.substring(0, 4));
            if (year >= 1950 && year <= 2008) score += 1;
        } catch (Exception ignored) {
        }

        return score;
    }


    // ---------- OCR.space call + image utilities ----------

    private byte[] toJpegBytes(BufferedImage image) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", baos);
        return baos.toByteArray();
    }

    private String callOcrSpaceForText(byte[] imageBytes) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            headers.set("apikey", apiKey);

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", new org.springframework.core.io.ByteArrayResource(imageBytes) {
                @Override
                public String getFilename() {
                    return "id-card.jpg";
                }
            });

            body.add("language", "eng");
            body.add("isOverlayRequired", "false");
            body.add("detectOrientation", "true");
            body.add("scale", "true");
            body.add("OCREngine", "2");

            HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(apiUrl, request, Map.class);

            if (response.getBody() == null) return "";

            Boolean errored = (Boolean) response.getBody().get("IsErroredOnProcessing");
            if (Boolean.TRUE.equals(errored)) return "";

            List<Map<String, Object>> parsedResults =
                    (List<Map<String, Object>>) response.getBody().get("ParsedResults");
            if (parsedResults == null || parsedResults.isEmpty()) return "";

            String parsedText = (String) parsedResults.get(0).get("ParsedText");
            return parsedText != null ? parsedText : "";
        } catch (Exception e) {
            System.err.println("[OCR.space] call failed: " + e.getMessage());
            return "";
        }
    }

    private String cleanNameTail(String name) {
        if (name == null) return "";
        String s = name.replaceAll("\\s+", " ").trim();
        // Remove trailing punctuation/garbage like "...-" or "--" etc.
        s = s.replaceAll("[^A-Za-z)]+$", "").trim();
        // If you allow commas/periods inside names:
        s = s.replaceAll("[\\s\\-\\.]{2,}", " ").trim();
        return s;
    }

    /**
     * Compress image to under 1MB for OCR.space free tier
     * Resizes and converts to JPEG with quality reduction
     */
    private byte[] compressImage(byte[] originalBytes) throws Exception {
        ByteArrayInputStream bais = new ByteArrayInputStream(originalBytes);
        BufferedImage originalImage = ImageIO.read(bais);

        if (originalImage == null) {
            return originalBytes;
        }

        // Convert to RGB for JPEG compatibility
        BufferedImage rgbImage = new BufferedImage(
                originalImage.getWidth(), originalImage.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = rgbImage.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, originalImage.getWidth(), originalImage.getHeight());
        g.drawImage(originalImage, 0, 0, null);
        g.dispose();
        originalImage = rgbImage;

        // Resize if too large
        int maxWidth = 1200;
        int width = originalImage.getWidth();
        int height = originalImage.getHeight();

        if (width > maxWidth) {
            double scale = (double) maxWidth / width;
            int newWidth = maxWidth;
            int newHeight = (int) (height * scale);

            BufferedImage resized = new BufferedImage(
                    newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g2 = resized.createGraphics();
            g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(originalImage, 0, 0, newWidth, newHeight, null);
            g2.dispose();
            originalImage = resized;
        }

        // Compress to JPEG
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        float quality = 0.8f;
        while (quality >= 0.3f) {
            baos.reset();

            javax.imageio.ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            javax.imageio.stream.ImageOutputStream ios = ImageIO.createImageOutputStream(baos);
            writer.setOutput(ios);

            javax.imageio.plugins.jpeg.JPEGImageWriteParam param =
                    new javax.imageio.plugins.jpeg.JPEGImageWriteParam(null);
            param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);

            writer.write(null, new javax.imageio.IIOImage(originalImage, null, null), param);
            writer.dispose();
            ios.close();

            if (baos.size() < 900 * 1024) break;
            quality -= 0.1f;
        }

        return baos.toByteArray();
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        Map<String, Object> err = new HashMap<>();
        err.put("error", message);
        err.put("success", false);
        return ResponseEntity.status(status).body(err);
    }
}