package pixelclipboard;

import java.awt.Font;
import java.awt.FontFormatException;
import java.io.IOException;

final class PixelFonts {
    private static final Font PIXEL = load();
    private PixelFonts() {}

    private static Font load() {
        try (var stream = PixelFonts.class.getResourceAsStream("/fonts/Silkscreen-Regular.ttf")) {
            if (stream == null) throw new IOException("Missing pixel font");
            return Font.createFont(Font.TRUETYPE_FONT,stream);
        } catch (IOException | FontFormatException exception) {
            throw new IllegalStateException("Cannot load the bundled pixel font",exception);
        }
    }

    static Font at(float size) { return PIXEL.deriveFont(Font.PLAIN,size); }
    static Font forText(String text) {
        Font font = at(16);
        // Keep existing multilingual tasks readable when a glyph is outside this font.
        return font.canDisplayUpTo(text) == -1 ? font : new Font(Font.MONOSPACED,Font.PLAIN,16);
    }
}
