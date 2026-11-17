import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 用 JDK 内置 Java2D 生成 Android 各密度启动图标。
 * 不需要任何第三方依赖，headless 模式即可运行。
 */
public class IconMaker {

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");

        File srcFile = new File(args[0]);
        File resRoot = new File(args[1]);

        BufferedImage src = ImageIO.read(srcFile);
        if (src == null) {
            throw new IllegalStateException("无法解码图片: " + srcFile);
        }
        System.out.println("原始尺寸: " + src.getWidth() + " x " + src.getHeight());

        // 裁掉底部 "AI dock" 字样，只保留图形标记
        int cx = (int) (src.getWidth() * 0.128);
        int cy = (int) (src.getHeight() * 0.010);
        int cw = (int) (src.getWidth() * 0.744);
        int ch = (int) (src.getHeight() * 0.744);
        cw = Math.min(cw, src.getWidth() - cx);
        ch = Math.min(ch, src.getHeight() - cy);
        BufferedImage mark = src.getSubimage(cx, cy, cw, ch);
        System.out.println("裁切区域: (" + cx + "," + cy + ") " + cw + "x" + ch);

        // 传统图标
        int[] legacy = {48, 72, 96, 144, 192};
        String[] legacyDir = {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};
        for (int i = 0; i < legacy.length; i++) {
            write(mark, new File(resRoot, "mipmap-" + legacyDir[i] + "/ic_launcher.png"), legacy[i], 0.94);
        }

        // 自适应图标前景（108dp 画布，图形占 60%，留出安全区）
        int[] fg = {108, 162, 216, 324, 432};
        for (int i = 0; i < fg.length; i++) {
            write(mark, new File(resRoot, "mipmap-" + legacyDir[i] + "/ic_launcher_foreground.png"), fg[i], 0.60);
        }

        // 应用商店用 512 图标
        write(mark, new File(args[2]), 512, 0.86);

        System.out.println("图标生成完成。");
    }

    private static void write(BufferedImage src, File out, int size, double ratio) throws Exception {
        BufferedImage dst = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = dst.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, size, size);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        int draw = (int) Math.round(size * ratio);
        int off = (size - draw) / 2;
        g.drawImage(src, off, off, draw, draw, null);
        g.dispose();

        File parent = out.getParentFile();
        if (parent != null) parent.mkdirs();
        ImageIO.write(dst, "png", out);
        System.out.println("  写入 " + out.getPath() + "  " + size + "x" + size);
    }
}
