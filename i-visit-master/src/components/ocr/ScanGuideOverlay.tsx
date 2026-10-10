import { useEffect, useState } from 'react';
import { getTemplateWithCustomRois, type RoiSpec } from '../../utils/cardTemplates';
import { computeGuideRect, CARD_ASPECT } from '../../utils/scanGuide';

interface ScanGuideOverlayProps {
  containerRef: React.RefObject<HTMLDivElement | null>;
  idType?: string;
  isCroppedPreview?: boolean;
  croppedAspect?: number;          // optional override
}

// Color mapping for different ROI field types
const ROI_COLORS: Record<string, string> = {
    fullName: '#00BFFF',    // Cyan/DeepSkyBlue
    idNumber: '#FF00FF',    // Magenta
    dob: '#FFD700',         // Gold
    institution: '#00FF7F', // SpringGreen
    faculty: '#FF6347',     // Tomato
};

/**
 * Visual scanning guide overlay for ID card alignment (Enhanced with ID-specific ROIs)
 * Shows a card-shaped guide with corner markers and field-specific ROI regions
 */
export default function ScanGuideOverlay({
  containerRef,
  idType,
  isCroppedPreview = false,
  croppedAspect,
}: ScanGuideOverlayProps) {
    const [dimensions, setDimensions] = useState({ width: 640, height: 480 });

    useEffect(() => {
        const updateDimensions = () => {
            if (containerRef.current) {
                const rect = containerRef.current.getBoundingClientRect();
                setDimensions({ width: rect.width, height: rect.height });
            }
        };

        updateDimensions();
        window.addEventListener('resize', updateDimensions);

        // Also observe the container for size changes
        const observer = new ResizeObserver(updateDimensions);
        if (containerRef.current) {
            observer.observe(containerRef.current);
        }

        return () => {
            window.removeEventListener('resize', updateDimensions);
            observer.disconnect();
        };
    }, [containerRef]);

    const { width, height } = dimensions;

    const warpAspect = croppedAspect ?? (1000 / 600); // matches cardCropper warp output
const aspect = isCroppedPreview ? warpAspect : CARD_ASPECT;

// Make the guide almost fill the cropped preview, but keep your live-camera sizing
const wFrac = isCroppedPreview ? 0.98 : undefined;
const hFrac = isCroppedPreview ? 0.98 : undefined;

const { x, y, width: guideWidth, height: guideHeight } = computeGuideRect(
  width,
  height,
  aspect,
  wFrac,
  hFrac
);

    // Corner marker size (proportional to guide size)
    const cornerSize = Math.min(guideWidth, guideHeight) * 0.1;
    const strokeWidth = 3;

    // Get ROI template for the selected ID type
    const template = idType ? getTemplateWithCustomRois(idType) : null;
    const rois: RoiSpec[] = template?.rois || [];

    // Helper to convert ROI normalized coords to SVG coords (relative to card guide)
    const roiToSvg = (roi: RoiSpec) => ({
        rx: x + roi.x * guideWidth,
        ry: y + roi.y * guideHeight,
        rw: roi.width * guideWidth,
        rh: roi.height * guideHeight,
    });

    return (
        <svg
            className="absolute inset-0 pointer-events-none"
            width={width}
            height={height}
            style={{ zIndex: 10 }}
        >
            {/* Semi-transparent overlay outside guide area */}
            {!isCroppedPreview && (
                <>
            <defs>
                <mask id="guideMask">
                    <rect width="100%" height="100%" fill="white" />
                    <rect
                        x={x}
                        y={y}
                        width={guideWidth}
                        height={guideHeight}
                        fill="black"
                        rx="8"
                    />
                </mask>
            </defs>
            <rect
                width="100%"
                height="100%"
                fill="rgba(0,0,0,0.4)"
                mask="url(#guideMask)"
            />
            </>
            )}

            {/* Guide border (yellow dashed) */}
            <rect
                x={x}
                y={y}
                width={guideWidth}
                height={guideHeight}
                fill="none"
                stroke="#FFD700"
                strokeWidth={strokeWidth}
                strokeDasharray="8,4"
                rx="8"
            />

            {/* ROI Field Regions - Rendered inside the card guide */}
            {rois.map((roi) => {
                const { rx, ry, rw, rh } = roiToSvg(roi);
                const color = ROI_COLORS[roi.key] || '#FFFFFF';

                return (
                    <g key={roi.key}>
                        {/* ROI Rectangle */}
                        <rect
                            x={rx}
                            y={ry}
                            width={rw}
                            height={rh}
                            fill={`${color}15`}  // 15 = ~8% opacity
                            stroke={color}
                            strokeWidth={2}
                            strokeDasharray="4,2"
                            rx="4"
                        />
                        {/* ROI Label */}
                        <text
                            x={rx + 4}
                            y={ry + 14}
                            fill={color}
                            fontSize="11"
                            fontWeight="600"
                            style={{ textShadow: '0 1px 2px rgba(0,0,0,0.9)' }}
                        >
                            {roi.label}
                        </text>
                    </g>
                );
            })}

            {/* Corner markers (green solid) */}
            {/* Top-left */}
            <path
                d={`M${x},${y + cornerSize} L${x},${y} L${x + cornerSize},${y}`}
                fill="none"
                stroke="#00FF00"
                strokeWidth={strokeWidth + 1}
                strokeLinecap="round"
            />
            {/* Top-right */}
            <path
                d={`M${x + guideWidth - cornerSize},${y} L${x + guideWidth},${y} L${x + guideWidth},${y + cornerSize}`}
                fill="none"
                stroke="#00FF00"
                strokeWidth={strokeWidth + 1}
                strokeLinecap="round"
            />
            {/* Bottom-left */}
            <path
                d={`M${x},${y + guideHeight - cornerSize} L${x},${y + guideHeight} L${x + cornerSize},${y + guideHeight}`}
                fill="none"
                stroke="#00FF00"
                strokeWidth={strokeWidth + 1}
                strokeLinecap="round"
            />
            {/* Bottom-right */}
            <path
                d={`M${x + guideWidth - cornerSize},${y + guideHeight} L${x + guideWidth},${y + guideHeight} L${x + guideWidth},${y + guideHeight - cornerSize}`}
                fill="none"
                stroke="#00FF00"
                strokeWidth={strokeWidth + 1}
                strokeLinecap="round"
            />

            {/* Instruction text */}
            <text
                x={width / 2}
                y={y - 12}
                textAnchor="middle"
                fill="#FFD700"
                fontSize="14"
                fontWeight="bold"
                style={{ textShadow: '0 1px 2px rgba(0,0,0,0.8)' }}
            >
                {idType && template
                    ? `Align ${template.displayName} within the frame`
                    : 'Position ID card within the frame'}
            </text>
        </svg>
    );
}
