package com.ivisit.helper.utils;

import java.awt.Rectangle;
import java.util.HashMap;
import java.util.Map;

/**
 * ROI (Region of Interest) templates for ID card field extraction.
 * Coordinates are stored as percentages of image dimensions (0.0 - 1.0).
 */
public class RoiTemplate {

    /**
     * Region definition with percentage coordinates
     */
    public static class Region {
        public final double xPct;
        public final double yPct;
        public final double widthPct;
        public final double heightPct;

        public Region(double xPct, double yPct, double widthPct, double heightPct) {
            this.xPct = xPct;
            this.yPct = yPct;
            this.widthPct = widthPct;
            this.heightPct = heightPct;
        }

        /**
         * Convert percentage coordinates to actual pixel Rectangle
         */
        public Rectangle toRectangle(int imageWidth, int imageHeight) {
            int x = (int) (xPct * imageWidth);
            int y = (int) (yPct * imageHeight);
            int w = (int) (widthPct * imageWidth);
            int h = (int) (heightPct * imageHeight);
            return new Rectangle(x, y, w, h);
        }
    }

    // ========== TEMPLATES ==========

    /**
     * Driver's License (Philippine LTO) - Standard layout
     */
    public static Map<String, Region> getDriversLicenseTemplate() {
        Map<String, Region> regions = new HashMap<>();
        // Name: Upper right area (35% from left, 23% from top)
        regions.put("fullName", new Region(0.32, 0.28, 0.65, 0.12));
        // License Number: Middle left area
        regions.put("idNumber", new Region(0.30, 0.61, 0.28, 0.12));
        // DOB: Middle area
        regions.put("dob", new Region(0.54, 0.38, 0.18, 0.12));
        return regions;
    }

    /**
     * SSS ID - Standard layout
     */
    public static Map<String, Region> getSSSIdTemplate() {
        Map<String, Region> regions = new HashMap<>();
        // Name: Center area
        regions.put("fullName", new Region(0.25, 0.35, 0.55, 0.12));
        // SSS Number: Below name
        regions.put("idNumber", new Region(0.25, 0.50, 0.40, 0.12));
        return regions;
    }

    /**
     * National ID (PhilSys) - Standard layout
     */
    public static Map<String, Region> getNationalIdTemplate() {
        Map<String, Region> regions = new HashMap<>();
        // Full Name: Middle right
        regions.put("fullName", new Region(0.40, 0.26, 0.55, 0.48));
        // PSN (ID Number): Upper middle
        regions.put("idNumber", new Region(0, 0.26, 0.40, 0.13));
        // DOB: Lower area
        regions.put("dob", new Region(0.40, 0.74, 0.40, 0.12));
        return regions;
    }

    /**
     * UMID - Similar to National ID with different proportions
     */
    public static Map<String, Region> getUMIDTemplate() {
        Map<String, Region> regions = new HashMap<>();
        // Surname, Given Name, Middle Name
        regions.put("fullName", new Region(0.38, 0.35, 0.62, 0.36));
        // CRN Number
        regions.put("idNumber", new Region(0.55, 0.23, 0.45, 0.18));
        // DOB
        regions.put("dob", new Region(0.62, 0.675, 0.235, 0.12));
        return regions;
    }

    /**
     * Passport - Standard layout (MRZ-based is best, but ROI helps if MRZ is weak)
     * NOTE: Coordinates vary by country/passport version. These are conservative.
     */
    public static Map<String, Region> getPassportTemplate() {
        Map<String, Region> regions = new HashMap<>();

        // Name (visual zone) - typically upper/middle area
        regions.put("fullName", new Region(0.28, 0.20, 0.40, 0.30));
        // Passport number (visual zone) - often upper right-ish, can vary
        // regions.put("idNumber", new Region(0.60, 0.12, 0.36, 0.12));
        // DOB (visual zone) - often near details area
        // regions.put("dob", new Region(0.28, 0.44, 0.30, 0.10));

        // These read off the MRZ lines at the bottom
        // Name (MRZ line 1) - typically lower area
        // regions.put("fullName", new Region(0.05, 0.79, 0.58, 0.10));
        // Passport (MRZ line 2) - typically lower area
        regions.put("idNumber", new Region(0.05, 0.86, 0.25, 0.13));
        // DOB (MRZ line 2) - typically lower area
        regions.put("dob", new Region(0.31, 0.86, 0.19, 0.13));

        return regions;
    }


    /**
     * Get template for a given ID type
     */
    public static Map<String, Region> getTemplate(String idType) {
        if (idType == null)
            return null;

        switch (idType.toLowerCase()) {
            case "driver's license":
            case "drivers license":
                return getDriversLicenseTemplate();
            case "sss id":
                return getSSSIdTemplate();
            case "national id":
                return getNationalIdTemplate();
            case "umid":
                return getUMIDTemplate();
            case "passport":
                return getPassportTemplate();
            default:
                return null;
        }
    }
}
