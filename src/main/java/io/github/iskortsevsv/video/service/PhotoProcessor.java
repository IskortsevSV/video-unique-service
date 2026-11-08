package io.github.iskortsevsv.video.service;

import java.awt.*;
import java.awt.image.*;
import java.io.IOException;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Service;

@Service
public class PhotoProcessor {
    public void process(Path input, Path output, double scale, boolean hard, int variant) throws IOException {
        try (var stream = ImageIO.createImageInputStream(input.toFile())) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IllegalArgumentException("Нужен корректный JPEG или PNG.");
            var reader = readers.next();
            try {
                if (!java.util.Set.of("JPEG", "JPG", "PNG").contains(reader.getFormatName().toUpperCase(java.util.Locale.ROOT)))
                    throw new IllegalArgumentException("Поддерживаются только JPEG и PNG.");
                reader.setInput(stream);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 2 || height < 2 || (long) width * height > 20_000_000)
                    throw new IllegalArgumentException("Фото должно быть не больше 20 мегапикселей.");
                var source = reader.read(0);
                var result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                var graphics = result.createGraphics();
                try {
                    graphics.setColor(Color.BLACK); graphics.fillRect(0, 0, width, height);
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    int w = Math.max(1, (int) (width * scale)), h = Math.max(1, (int) (height * scale));
                    graphics.drawImage(source, (width - w) / 2, (height - h) / 2, w, h, null);
                } finally { graphics.dispose(); source.flush(); }
                // Small tonal variation also distinguishes tiny images whose scale rounds to the same size.
                var adjusted = new RescaleOp(hard ? 1.04f : 1.0f, (hard ? 3 : 0) + variant, null).filter(result, null);
                if (!ImageIO.write(adjusted, "jpg", output.toFile())) throw new IOException("JPEG encoder unavailable");
                adjusted.flush(); result.flush();
            } finally { reader.dispose(); }
        }
    }
}
