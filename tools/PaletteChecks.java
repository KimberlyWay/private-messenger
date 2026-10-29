import uk.mywisp.material.hct.Hct;
import uk.mywisp.material.scheme.SchemeTonalSpot;
import uk.mywisp.material.dynamiccolor.ColorSpec.SpecVersion;
import uk.mywisp.material.dynamiccolor.DynamicScheme.Platform;
import uk.mywisp.material.contrast.Contrast;
import uk.mywisp.material.utils.ColorUtils;

/** Runs on a plain JDK as well as in Android instrumentation. */
public final class PaletteChecks {
    private static double ratio(int a, int b) {
        return Contrast.ratioOfYs(ColorUtils.xyzFromArgb(a)[1],
            ColorUtils.xyzFromArgb(b)[1]);
    }
    public static void main(String[] args) {
        int checked = 0;
        for (boolean dark : new boolean[] {false, true}) {
            for (double contrast : new double[] {0, .5, 1}) {
                for (int hue = 0; hue < 360; hue += 10) {
                    SchemeTonalSpot s = new SchemeTonalSpot(Hct.from(hue, 60, 50), dark, contrast,
                        SpecVersion.SPEC_2026, Platform.PHONE);
                    if (ratio(s.getPrimary(), s.getOnPrimary()) < 4.45
                        || ratio(s.getSurface(), s.getOnSurface()) < 4.45
                        || ratio(s.getPrimaryContainer(), s.getOnPrimaryContainer()) < 4.45) {
                        throw new AssertionError("Unreadable palette: hue=" + hue + ", dark=" + dark + ", contrast=" + contrast);
                    }
                    checked++;
                }
            }
            SchemeTonalSpot ocean = new SchemeTonalSpot(Hct.fromInt(0xff006783), dark, 0, SpecVersion.SPEC_2026, Platform.PHONE);
            SchemeTonalSpot forest = new SchemeTonalSpot(Hct.fromInt(0xff286b42), dark, 0, SpecVersion.SPEC_2026, Platform.PHONE);
            if (ocean.getPrimary() == forest.getPrimary() || ocean.getSecondaryContainer() == forest.getSecondaryContainer()
                || ocean.getSurfaceContainerLow() == forest.getSurfaceContainerLow()) {
                throw new AssertionError("Theme must change complete color roles, not only primary");
            }
        }
        System.out.println("PASS: " + checked + " Material 2026 palettes, text contrast, Ocean/Forest surface and container separation");
    }
}
