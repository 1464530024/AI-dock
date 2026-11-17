import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Iterator;

/**
 * 把真机截图 PNG 转成高质量 JPG，用于内嵌到 HTML 展示页。
 * 截图是 UI 平涂内容，JPG q=0.88 视觉无损，体积约为 PNG 的 1/3。
 */
public class ShotToJpg {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("用法: ShotToJpg <源目录> <输出目录>");
            return;
        }
        File srcDir = new File(args[0]);
        File outDir = new File(args[1]);
        if (!outDir.exists()) outDir.mkdirs();

        File[] files = srcDir.listFiles((d, n) -> n.toLowerCase().endsWith(".png"));
        if (files == null) {
            System.out.println("源目录无 PNG");
            return;
        }
        java.util.Arrays.sort(files);

        Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("jpg");
        ImageWriter writer = it.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(0.88f);

        final int targetW = 540;

        for (File f : files) {
            if (f.getName().startsWith("_")) continue;
            BufferedImage src = ImageIO.read(f);
            if (src == null) continue;

            String outName = f.getName().replaceAll("\\.png$", ".jpg");
            File out = new File(outDir, outName);

            // 等比缩放到目标宽度，高质量双三次插值
            int w = targetW;
            int h = (int) Math.round(src.getHeight() * (targetW / (double) src.getWidth()));
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = img.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING,
                    java.awt.RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.drawImage(src, 0, 0, w, h, null);
            g.dispose();
            BufferedImage rgb = img;

            ImageOutputStream ios = ImageIO.createImageOutputStream(out);
            writer.setOutput(ios);
            writer.write(null, new IIOImage(rgb, null, null), param);
            ios.close();

            System.out.printf("  %-28s %dx%d  %6.1f KB -> %6.1f KB%n",
                    outName, img.getWidth(), img.getHeight(),
                    f.length() / 1024.0, out.length() / 1024.0);
        }
        writer.dispose();
        System.out.println("完成");
    }
}
