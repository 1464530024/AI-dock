import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/** 采样每个 LOGO 主色调，输出可直接写进 AssistantCatalog 的十六进制色值 */
public class ColorProbe {
    public static void main(String[] a) throws Exception {
        File dir = new File(a[0]);
        String[] ids = {"doubao", "qwen", "yuanbao", "deepseek", "kimi", "spark", "chatglm", "ernie"};
        for (String id : ids) {
            File f = new File(dir, "logo_" + id + ".png");
            if (!f.exists()) continue;
            BufferedImage img = ImageIO.read(f);
            int w = img.getWidth(), h = img.getHeight();
            long sr = 0, sg = 0, sb = 0; int n = 0;
            int[] hist = new int[24];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int p = img.getRGB(x, y);
                    if (((p >>> 24) & 0xFF) < 200) continue;
                    int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                    float[] hsb = java.awt.Color.RGBtoHSB(r, g, b, null);
                    if (hsb[1] < 0.30f || hsb[2] < 0.15f || hsb[2] > 0.97f) continue;  // 跳过灰白
                    sr += r; sg += g; sb += b; n++;
                    hist[(int) (hsb[0] * 24) % 24]++;
                }
            }
            if (n == 0) { System.out.printf("%-9s 无明显彩色像素%n", id); continue; }
            int domBin = 0;
            for (int i = 1; i < 24; i++) if (hist[i] > hist[domBin]) domBin = i;
            System.out.printf("%-9s #%02X%02X%02X   (采样 %d px, 主色相 %.0f°)%n",
                    id, sr / n, sg / n, sb / n, n, domBin * 15.0 + 7.5);
        }
    }
}
