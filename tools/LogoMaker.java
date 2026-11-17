import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 把 8 个 AI 助手的官方 LOGO 源图，统一处理成 Android 各密度的圆形头像资源。
 *
 * 处理规则：
 *  1. 若图片含透明像素，则按 alpha 计算内容包围盒；
 *  2. 包围盒接近整图（>=90%）视为"满版方形图标"，缩放到 100% 后圆形裁切（圆外露出的是图标自身的角，被圆切掉）；
 *  3. 包围盒明显偏小（如 DeepSeek 鲸鱼、星火火焰），按包围盒裁剪后缩放到 92% 居中；
 *  4. 小图（放大倍数 >= 2）额外做一次 USM 锐化补偿；
 *  5. 输出 48/72/96/144/192 五档，对应 mdpi..xxxhdpi。
 */
public class LogoMaker {

    static final int[] SIZES = {48, 72, 96, 144, 192};
    static final String[] DENS = {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};

    // {id, 源文件名, 是否纯黑单色}
    // 单色标记（OpenAI 六瓣结）在深色主题下会消失在深色底上，
    // 由 App 侧用 ColorFilter.tint 跟随 onSurface 重新着色，这里只需保证 alpha 干净。
    static final String[][] SPECS = {
            {"doubao", "doubao_192.png", "0"},
            {"qwen", "qwen_150.png", "0"},
            {"yuanbao", "yuanbao_128x128.png", "0"},
            {"deepseek", "deepseek_180.png", "0"},
            {"kimi", "kimi_pwa-512.png", "0"},
            {"spark", "_spark_render.png", "0"},
            {"chatglm", "chatglm_src.png", "0"},
            {"ernie", "wenxin_512.png", "0"},
            // 国外 4 个（源图已由 build_render.py 渲染为 512px 带内边距 PNG）
            {"chatgpt", "intl/chatgpt_src.png", "1"},
            {"gemini", "intl/gemini_src.png", "0"},
            {"claude", "intl/claude_src.png", "0"},
            {"copilot", "intl/copilot_src.png", "0"},
    };

    public static void main(String[] args) throws Exception {
        File srcDir = new File(args[0]);
        File resDir = new File(args[1]);

        for (String[] spec : SPECS) {
            String id = spec[0];
            File f = new File(srcDir, spec[1]);
            BufferedImage src = ImageIO.read(f);
            if (src == null) {
                System.out.println("FAIL  读不到 " + spec[1]);
                continue;
            }
            BufferedImage work = toArgb(src);

            // Kimi 的 512 原图黑底上散布灰白颗粒，把低饱和的中间灰压成纯黑
            if ("kimi".equals(id)) {
                crushGrayNoise(work);
            }
            // DeepSeek 的 180px 原图带不透明白底，深色主题下会变成刺眼白圆，从四边泛洪抠掉
            if ("deepseek".equals(id)) {
                removeBorderWhite(work);
                featherWhiteEdge(work, 196);
            }

            Crop crop = planCrop(work);
            double upscale = (double) SIZES[SIZES.length - 1] / Math.max(crop.w, crop.h);
            boolean sharpen = upscale >= 1.8;

            for (int i = 0; i < SIZES.length; i++) {
                BufferedImage out = renderCircle(work, crop, SIZES[i], sharpen);
                File dir = new File(resDir, "drawable-" + DENS[i]);
                if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("mkdir 失败 " + dir);
                ImageIO.write(out, "png", new File(dir, "logo_" + id + ".png"));
            }
            System.out.printf("%-9s %-20s 源 %dx%d  裁剪 %dx%d@(%d,%d)  放大 %.1fx  锐化 %s%n",
                    id, spec[1], src.getWidth(), src.getHeight(),
                    crop.w, crop.h, crop.x, crop.y, upscale, sharpen ? "是" : "否");
        }
        System.out.println("完成");
    }

    static BufferedImage toArgb(BufferedImage src) {
        BufferedImage b = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = b.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return b;
    }

    /** 计算内容包围盒与缩放策略 */
    static Crop planCrop(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int minX = w, minY = h, maxX = -1, maxY = -1;
        boolean anyTransparent = false;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int a = (img.getRGB(x, y) >>> 24) & 0xFF;
                if (a < 250) anyTransparent = true;
                if (a > 8) {
                    if (x < minX) minX = x;
                    if (y < minY) minY = y;
                    if (x > maxX) maxX = x;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < 0) return new Crop(0, 0, w, h, 1.0f);   // 全透明异常，退化处理

        int bw = maxX - minX + 1, bh = maxY - minY + 1;
        // 满版：内容几乎铺满整图，按原图整体缩放，靠圆形裁切去掉四角
        if (!anyTransparent || (bw >= w * 0.90 && bh >= h * 0.90)) {
            return new Crop(0, 0, w, h, 1.0f);
        }
        return new Crop(minX, minY, bw, bh, 0.92f);
    }

    static BufferedImage renderCircle(BufferedImage src, Crop crop, int size, boolean sharpen) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        // 圆形裁切区域
        g.setClip(new Ellipse2D.Float(0, 0, size, size));

        // 内容绘制尺寸：满版 100%，非满版 92% 居中
        double box = size * crop.ratio;
        double scale = box / Math.max(crop.w, crop.h);
        int dw = (int) Math.round(crop.w * scale);
        int dh = (int) Math.round(crop.h * scale);
        int dx = (size - dw) / 2;
        int dy = (size - dh) / 2;

        g.setComposite(AlphaComposite.SrcOver);
        g.drawImage(src.getSubimage(crop.x, crop.y, crop.w, crop.h), dx, dy, dw, dh, null);
        g.dispose();

        return sharpen ? unsharp(out, 0.55f) : out;
    }

    /** 轻量 USM：原图 + (原图 - 模糊) * amount */
    static BufferedImage unsharp(BufferedImage img, float amount) {
        int w = img.getWidth(), h = img.getHeight();
        BufferedImage blur = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = blur.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(img, 0, 0, w, h, null);
        g.dispose();
        blur = boxBlur(blur);

        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = img.getRGB(x, y), q = blur.getRGB(x, y);
                int a = (p >>> 24) & 0xFF;
                if (a == 0) { out.setRGB(x, y, 0); continue; }
                int r = clampC(((p >> 16) & 0xFF) + amount * (((p >> 16) & 0xFF) - ((q >> 16) & 0xFF)));
                int gg = clampC(((p >> 8) & 0xFF) + amount * (((p >> 8) & 0xFF) - ((q >> 8) & 0xFF)));
                int b = clampC((p & 0xFF) + amount * ((p & 0xFF) - (q & 0xFF)));
                out.setRGB(x, y, (a << 24) | (r << 16) | (gg << 8) | b);
            }
        }
        return out;
    }

    static BufferedImage boxBlur(BufferedImage src) {
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                long r = 0, g = 0, b = 0, a = 0; int n = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = Math.min(h - 1, Math.max(0, y + dy));
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = Math.min(w - 1, Math.max(0, x + dx));
                        int p = src.getRGB(xx, yy);
                        r += (p >> 16) & 0xFF; g += (p >> 8) & 0xFF; b += p & 0xFF; a += (p >>> 24) & 0xFF; n++;
                    }
                }
                out.setRGB(x, y, ((int) (a / n) << 24) | ((int) (r / n) << 16) | ((int) (g / n) << 8) | (int) (b / n));
            }
        }
        return out;
    }

    /** 3x3 中值滤波，用于去除 Kimi 原图的颗粒噪点 */
    static BufferedImage median3(BufferedImage src) {
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] rs = new int[9], gs = new int[9], bs = new int[9], as = new int[9];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int n = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = Math.min(h - 1, Math.max(0, y + dy));
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = Math.min(w - 1, Math.max(0, x + dx));
                        int p = src.getRGB(xx, yy);
                        rs[n] = (p >> 16) & 0xFF; gs[n] = (p >> 8) & 0xFF; bs[n] = p & 0xFF; as[n] = (p >>> 24) & 0xFF;
                        n++;
                    }
                }
                java.util.Arrays.sort(rs); java.util.Arrays.sort(gs); java.util.Arrays.sort(bs); java.util.Arrays.sort(as);
                out.setRGB(x, y, (as[4] << 24) | (rs[4] << 16) | (gs[4] << 8) | bs[4]);
            }
        }
        return out;
    }

    static int clampC(double v) { return (int) Math.max(0, Math.min(255, Math.round(v))); }

    /**
     * 从四边泛洪，把与边缘连通的近白像素置为透明。
     * 只处理连通区域，所以 Logo 内部的白（比如鲸鱼的眼睛）不会被误伤。
     */
    static void removeBorderWhite(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        boolean[] seen = new boolean[w * h];
        java.util.ArrayDeque<int[]> q = new java.util.ArrayDeque<>();

        for (int x = 0; x < w; x++) { pushIfWhite(img, q, seen, x, 0); pushIfWhite(img, q, seen, x, h - 1); }
        for (int y = 0; y < h; y++) { pushIfWhite(img, q, seen, 0, y); pushIfWhite(img, q, seen, w - 1, y); }

        int cleared = 0;
        while (!q.isEmpty()) {
            int[] p = q.poll();
            int x = p[0], y = p[1];
            img.setRGB(x, y, img.getRGB(x, y) & 0x00FFFFFF);   // alpha 归零
            cleared++;
            if (x > 0) pushIfWhite(img, q, seen, x - 1, y);
            if (x < w - 1) pushIfWhite(img, q, seen, x + 1, y);
            if (y > 0) pushIfWhite(img, q, seen, x, y - 1);
            if (y < h - 1) pushIfWhite(img, q, seen, x, y + 1);
        }
        System.out.println("         抠掉边缘白底 " + cleared + " px");
    }

    static void pushIfWhite(BufferedImage img, java.util.ArrayDeque<int[]> q, boolean[] seen, int x, int y) {
        int w = img.getWidth();
        int idx = y * w + x;
        if (seen[idx]) return;
        int p = img.getRGB(x, y);
        if ((p >>> 24) < 8) { seen[idx] = true; return; }        // 已透明，跳过但标记
        int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
        if (r > 232 && g > 232 && b > 232) {
            seen[idx] = true;
            q.add(new int[]{x, y});
        }
    }

    /**
     * 去掉抠底后残留的白毛边：紧贴透明区、且自身接近纯白的不透明像素一并清掉。
     */
    static void featherWhiteEdge(BufferedImage img, int threshold) {
        int w = img.getWidth(), h = img.getHeight();
        // 跑两遍，吃掉白毛边的第二层
        int total = 0;
        for (int pass = 0; pass < 2; pass++) {
            boolean[] clear = new boolean[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int p = img.getRGB(x, y);
                    if (((p >>> 24) & 0xFF) < 8) continue;
                    int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                    if (r < threshold || g < threshold || b < threshold) continue;
                    if (hasTransparentNeighbor(img, x, y)) clear[y * w + x] = true;
                }
            }
            for (int i = 0; i < clear.length; i++) {
                if (clear[i]) {
                    int x = i % w, y = i / w;
                    img.setRGB(x, y, img.getRGB(x, y) & 0x00FFFFFF);
                    total++;
                }
            }
        }
        System.out.println("         羽化白边 " + total + " px");
    }

    static boolean hasTransparentNeighbor(BufferedImage img, int x, int y) {
        int w = img.getWidth(), h = img.getHeight();
        int[][] d = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        for (int[] o : d) {
            int xx = x + o[0], yy = y + o[1];
            if (xx < 0 || yy < 0 || xx >= w || yy >= h) return true;
            if (((img.getRGB(xx, yy) >>> 24) & 0xFF) < 128) return true;
        }
        return false;
    }

    /**
     * 把低饱和、中等亮度的灰色像素压成纯黑，用于清除黑底上的颗粒噪点。
     * 纯白的 K 字（亮度接近 255）与高饱和的蓝点不受影响。
     */
    static void crushGrayNoise(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int hit = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = img.getRGB(x, y);
                int a = (p >>> 24) & 0xFF;
                if (a < 8) continue;
                int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
                int sat = max - min;
                if (sat < 34 && max > 18 && max < 168) {          // 灰颗粒
                    img.setRGB(x, y, (a << 24));                   // 纯黑
                    hit++;
                }
            }
        }
        System.out.println("         压制灰颗粒 " + hit + " px");
    }

    static class Crop {
        final int x, y, w, h;
        final float ratio;
        Crop(int x, int y, int w, int h, float ratio) { this.x = x; this.y = y; this.w = w; this.h = h; this.ratio = ratio; }
    }
}
