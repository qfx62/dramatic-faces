package dev.dramaticfaces;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import org.jspecify.annotations.Nullable;

/**
 * Where a skin has its eyes and what colors they are, read from the skin's own
 * pixels, so the eyes can blink and look around without changing how they look.
 *
 * <p>Face pixels are columns 0 to 7 (left to right as you look at the player)
 * and rows 0 to 7 (top to bottom). In model units column c is x = c - 4 and
 * row r is y = r - 8.
 *
 * @param row the face row the eyes are on
 * @param tone the skin color around the eyes, used for eyelids
 * @param overlay whether the eyes are drawn on the hat layer, which sits a bit in front of the face
 */
record SkinEyes(int row, Eye left, Eye right, int tone, boolean overlay) {
	/**
	 * One eye.
	 *
	 * @param x0 the first face column of the eye
	 * @param width how many pixels wide it is
	 * @param white the eye's background color (the skin tone if it has no white)
	 * @param pupil the pupil color
	 * @param pupilAt which pixel of the eye the pupil is on, 0 = leftmost
	 */
	record Eye(int x0, int width, int white, int pupil, int pupilAt) {
	}

	/** Steve's eyes, used until (or if) a skin can't be read. */
	static final SkinEyes DEFAULT = new SkinEyes(4, new Eye(1, 2, 0xFFFFFFFF, 0xFF523D89, 1),
			new Eye(5, 2, 0xFFFFFFFF, 0xFF523D89, 0), 0xFFB4846D, false);

	private static final Map<String, SkinEyes> CACHE = new HashMap<>();
	private static final Map<String, Long> RETRY = new HashMap<>();
	/** Colors further apart than this (sum of the red, green and blue differences) count as different. */
	private static final int DIFFERENT = 90;

	/** The eyes of this skin; read once, then remembered. */
	static SkinEyes of(Identifier texture, boolean hat) {
		String key = texture + (hat ? "+hat" : "");
		SkinEyes known = CACHE.get(key);
		if (known != null) {
			return known;
		}
		long now = System.nanoTime();
		Long retry = RETRY.get(key);
		if (retry != null && now < retry) {
			return DEFAULT;
		}
		Pixels pixels = pixels(texture);
		if (pixels == null) {
			// Downloaded skins can take a moment to arrive.
			RETRY.put(key, now + 2_000_000_000L);
			return DEFAULT;
		}
		SkinEyes eyes = find(pixels, hat);
		CACHE.put(key, eyes);
		return eyes;
	}

	private interface Pixels {
		/** ARGB color at x, y of the skin. */
		int get(int x, int y);
	}

	private static @Nullable Pixels pixels(Identifier texture) {
		Minecraft minecraft = Minecraft.getInstance();
		AbstractTexture loaded = minecraft.getTextureManager().getTexture(texture);
		if (loaded instanceof DynamicTexture dynamic) {
			NativeImage image = dynamic.getPixels();
			return image == null || image.isClosed() ? null : copy(image);
		}
		// Built-in skins (Steve, Alex, ...) are plain resources.
		Optional<Resource> resource = minecraft.getResourceManager().getResource(texture);
		if (resource.isEmpty()) {
			return null;
		}
		try (InputStream in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
			return copy(image);
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/** Copies just what's needed, so nothing depends on the image staying open. */
	private static Pixels copy(NativeImage image) {
		int width = image.getWidth();
		int scale = Math.max(1, width / 64);
		// The face is at x 8-15, the hat layer's face at x 40-47, both at y 8-15.
		int[] face = new int[64];
		int[] hat = new int[64];
		for (int r = 0; r < 8; r++) {
			for (int c = 0; c < 8; c++) {
				face[r * 8 + c] = image.getPixel((8 + c) * scale, (8 + r) * scale);
				hat[r * 8 + c] = image.getPixel((40 + c) * scale, (8 + r) * scale);
			}
		}
		return (x, y) -> x >= 40 ? hat[(y - 8) * 8 + (x - 40)] : face[(y - 8) * 8 + (x - 8)];
	}

	private static SkinEyes find(Pixels pixels, boolean hat) {
		int[] face = new int[64];
		boolean[] fromHat = new boolean[64];
		for (int r = 0; r < 8; r++) {
			for (int c = 0; c < 8; c++) {
				int over = pixels.get(40 + c, 8 + r);
				boolean covered = hat && (over >>> 24) > 128;
				face[r * 8 + c] = covered ? over : pixels.get(8 + c, 8 + r) | 0xFF000000;
				fromHat[r * 8 + c] = covered;
			}
		}
		int tone = skinTone(face);

		// The row whose left and right halves each have a small patch that isn't skin: that's the eyes.
		int bestRow = -1;
		float bestScore = 1.9f;
		for (int r = 2; r <= 6; r++) {
			float score = side(face, tone, r, 0) + side(face, tone, r, 4) + (r == 4 ? 0.3f : 0.0f);
			if (score > bestScore) {
				bestScore = score;
				bestRow = r;
			}
		}
		if (bestRow < 0) {
			return DEFAULT;
		}
		Eye left = eye(face, tone, bestRow, 0);
		Eye right = eye(face, tone, bestRow, 4);
		boolean overlay = fromHat[bestRow * 8 + left.x0()] || fromHat[bestRow * 8 + right.x0()];
		SkinEyes eyes = new SkinEyes(bestRow, left, right, tone, overlay);
		if (Face.DEBUG) {
			System.out.println("[Faces] skin eyes " + eyes + " tone " + Integer.toHexString(tone));
		}
		return eyes;
	}

	/** How eye-like one half of a row looks: 1 for a patch of 1 to 3 non-skin pixels, more if one of them is white. */
	private static float side(int[] face, int tone, int r, int from) {
		int count = 0;
		boolean white = false;
		for (int c = from; c < from + 4; c++) {
			int color = face[r * 8 + c];
			if (distance(color, tone) > DIFFERENT) {
				count++;
				white |= brightness(color) > 200 && saturation(color) < 40;
			}
		}
		if (count < 1 || count > 3) {
			return 0.0f;
		}
		return white ? 1.4f : 1.0f;
	}

	private static Eye eye(int[] face, int tone, int r, int from) {
		// The run of non-skin pixels closest to the middle of this half.
		float middle = from + 1.5f;
		int bestStart = -1;
		int bestLength = 0;
		float bestDistance = Float.MAX_VALUE;
		for (int c = from; c < from + 4; ) {
			if (distance(face[r * 8 + c], tone) <= DIFFERENT) {
				c++;
				continue;
			}
			int start = c;
			while (c < from + 4 && distance(face[r * 8 + c], tone) > DIFFERENT) {
				c++;
			}
			float d = Math.abs((start + c - 1) / 2.0f - middle);
			if (d < bestDistance) {
				bestDistance = d;
				bestStart = start;
				bestLength = c - start;
			}
		}
		int dark = bestStart;
		int light = bestStart;
		for (int c = bestStart; c < bestStart + bestLength; c++) {
			if (brightness(face[r * 8 + c]) < brightness(face[r * 8 + dark])) {
				dark = c;
			}
			if (brightness(face[r * 8 + c]) > brightness(face[r * 8 + light])) {
				light = c;
			}
		}
		if (bestLength == 1) {
			// A one pixel dot: give it room to look around, toward the middle of the face.
			boolean leftHalf = from == 0;
			return new Eye(leftHalf ? bestStart : bestStart - 1, 2, tone, face[r * 8 + bestStart], leftHalf ? 0 : 1);
		}
		return new Eye(bestStart, bestLength, face[r * 8 + light], face[r * 8 + dark], dark - bestStart);
	}

	/** The most common color on the lower face, roughly. */
	private static int skinTone(int[] face) {
		Map<Integer, int[]> buckets = new HashMap<>();
		for (int i = 8; i < 64; i++) {
			int color = face[i];
			int key = (color >> 4 & 0x0F0F0F);
			int[] sum = buckets.computeIfAbsent(key, k -> new int[4]);
			sum[0] += color >> 16 & 0xFF;
			sum[1] += color >> 8 & 0xFF;
			sum[2] += color & 0xFF;
			sum[3]++;
		}
		int[] best = null;
		for (int[] sum : buckets.values()) {
			if (best == null || sum[3] > best[3]) {
				best = sum;
			}
		}
		return 0xFF000000 | best[0] / best[3] << 16 | best[1] / best[3] << 8 | best[2] / best[3];
	}

	static int distance(int a, int b) {
		return Math.abs((a >> 16 & 0xFF) - (b >> 16 & 0xFF)) + Math.abs((a >> 8 & 0xFF) - (b >> 8 & 0xFF))
				+ Math.abs((a & 0xFF) - (b & 0xFF));
	}

	private static int brightness(int color) {
		return ((color >> 16 & 0xFF) * 3 + (color >> 8 & 0xFF) * 6 + (color & 0xFF)) / 10;
	}

	private static int saturation(int color) {
		int r = color >> 16 & 0xFF;
		int g = color >> 8 & 0xFF;
		int b = color & 0xFF;
		return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
	}

	/** A darker version of a color, for eyelashes. */
	static int darker(int color) {
		return 0xFF000000 | (color >> 16 & 0xFF) / 3 << 16 | (color >> 8 & 0xFF) / 3 << 8 | (color & 0xFF) / 3;
	}
}
