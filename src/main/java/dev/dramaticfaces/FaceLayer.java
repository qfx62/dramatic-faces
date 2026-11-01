package dev.dramaticfaces;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Draws the eyes, mouth and brows on the front of a player's head, in the
 * same pixel size as the skin so it looks like part of it, plus little emotion
 * icons floating above the head (anger mark, "HA", "!!", "...", Z's).
 *
 * <p>Face coordinates are skin pixels: x from -4 to 4, y from -8 (top of the
 * head) to 0 (chin). Most skins have their eyes on row y = -4 and their mouth
 * on y = -2.
 */
public class FaceLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
	private static final Identifier WHITE = Identifier.fromNamespaceAndPath("dramaticfaces", "textures/white.png");
	private static final int DARK = 0xFF2A0F0F;
	private static final int TONGUE = 0xFFB0383A;
	private static final int TEETH = 0xFFF2EEE6;
	private static final int BROW = 0xFF2B2018;
	private static final int SWEAT = 0xFF8FD3FF;
	private static final int ANGER = 0xFFE02828;
	private static final int LAUGH = 0xFFFFD34A;
	private static final int ALARM = 0xFFFF4040;
	private static final int QUIET = 0xFFB8B8C8;
	private static final int SLEEP = 0xFFCFE6FF;
	private static final int CLOUD = 0xFF8C96A8;
	/** Just in front of the skin's face, behind the hat layer. */
	private static final float FACE_Z = -4.02f;
	/** The hat layer's box is this much bigger than the head. */
	private static final float HAT = 1.125f;

	// Little pixel icons; X is a filled pixel.
	private static final String[] ANGER_MARK = {
			".XX.XX.",
			"X.....X",
			"X.....X",
			".......",
			"X.....X",
			"X.....X",
			".XX.XX."
	};
	private static final String[] HA = {
			"X.X..X.",
			"X.X.X.X",
			"XXX.XXX",
			"X.X.X.X",
			"X.X.X.X"
	};
	private static final String[] ALARM_MARK = {
			"X.X",
			"X.X",
			"X.X",
			"...",
			"X.X"
	};
	private static final String[] DOTS = {"X.X.X"};
	private static final String[] RAIN_CLOUD = {
		"..XX...",
		".XXXXX.",
		"XXXXXXX",
	};
	private static final String[] Z = {
			"XXXX",
			"..X.",
			".X..",
			"XXXX"
	};

	private record Pixel(float x, float y, float w, float h, int color, float z) {
	}

	public FaceLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
		super(parent);
	}

	@Override
	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, AvatarRenderState state, float yRot, float xRot) {
		if (state.isInvisible) {
			return;
		}
		Face face = Faces.get(state.id);
		if (face == null) {
			return;
		}
		FacesConfig config = FacesConfig.get();
		poseStack.pushPose();
		getParentModel().getHead().translateAndRotate(poseStack);
		poseStack.scale(1.0f / 16.0f, 1.0f / 16.0f, 1.0f / 16.0f);

		// A skull, pumpkin or block on the head hides the face.
		if (state.headItem.isEmpty() && state.wornHeadType == null) {
			List<Pixel> pixels = paint(face, config, state);
			if (!pixels.isEmpty()) {
				collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(WHITE), (pose, buffer) -> {
					for (Pixel p : pixels) {
						quad(pose, buffer, p, light, -1.0f);
					}
				});
			}
		}

		if (config.particles) {
			List<Pixel> icons = icons(face, config);
			if (!icons.isEmpty()) {
				// A point above the head, then turned to face the camera like a name tag.
				poseStack.pushPose();
				poseStack.translate(0.0f, -10.0f, 0.0f);
				Vector3f above = poseStack.last().pose().getTranslation(new Vector3f());
				poseStack.popPose();
				PoseStack billboard = new PoseStack();
				billboard.translate(above.x, above.y, above.z);
				billboard.mulPose(new Matrix4f().rotation(Minecraft.getInstance().gameRenderer.mainCamera().rotation()));
				float pixel = 1.0f / 16.0f;
				billboard.scale(pixel, -pixel, pixel);
				collector.submitCustomGeometry(billboard, RenderTypes.entityCutout(WHITE), (pose, buffer) -> {
					for (Pixel p : icons) {
						quad(pose, buffer, p, LightCoordsUtil.FULL_BRIGHT, 1.0f);
					}
				});
			}
		}
		poseStack.popPose();
	}

	/** The eyes, mouth and brows for this frame, as pixel rectangles. */
	private static List<Pixel> paint(Face face, FacesConfig config, AvatarRenderState state) {
		List<Pixel> out = new ArrayList<>();
		float open = face.mouth();
		float t = face.time();
		float m = config.mouthOffset;
		boolean mouth = config.showMouth;
		boolean brows = config.showBrows;
		SkinEyes skin = SkinEyes.of(state.skin.body().texturePath(), state.showHat);
		// Brows sit just above the eyes, wherever this skin has them.
		float b = skin.row() - 4;
		if (config.eyes) {
			eyes(out, face, skin);
		}
		Face.Expression expression = face.expression();
		switch (expression) {
			case NEUTRAL, TALK, AWKWARD, WORRIED -> {
				if (mouth) {
					if (open > 0.12f) {
						talk(out, face, config, open, m);
					} else if (expression == Face.Expression.AWKWARD) {
						// A tight little line, pulled to one side.
						rect(out, -1, -1.8f + m, 2.5f, 0.4f, DARK, 0);
					} else if (expression == Face.Expression.WORRIED) {
						// A wobbly mouth.
						for (int i = 0; i < 4; i++) {
							rect(out, -2 + i, (i % 2 == 0 ? -1.8f : -2.1f) + m, 1, 0.4f, DARK, 0);
						}
					}
				}
				if (brows && face.reaction() == Face.Reaction.WORRIED) {
					sadBrows(out, b);
				}
				if (expression == Face.Expression.AWKWARD && config.particles) {
					float drip = (t * 0.7f) % 1.0f;
					rect(out, 3.2f, -6.5f + b + drip * 2.0f, 0.6f, 1.0f, SWEAT, 1);
				}
			}
			case WHISPER -> {
				if (mouth && open > 0.08f) {
					rect(out, -0.5f, -2 + m, 1, 1, DARK, 0);
				}
			}
			case SHOUT -> {
				if (mouth) {
					float drop = 0.5f * open;
					rect(out, -2, -2.5f + m, 4, 0.5f, TEETH, 1);
					rect(out, -2, -2.5f + m, 4, 2.0f + drop, DARK, 0);
					rect(out, -1, -1.0f + drop + m, 2, 0.5f, TONGUE, 1);
				}
				if (brows) {
					angryBrows(out, b);
				}
			}
			case LAUGH -> {
				if (mouth) {
					// A wide grin with the corners up, bouncing open with every "ha".
					rect(out, -3, -3 + m, 1, 1, DARK, 0);
					rect(out, 2, -3 + m, 1, 1, DARK, 0);
					rect(out, -2, -2 + m, 4, 1, DARK, 0);
					rect(out, -2, -2 + m, 4, 0.4f, TEETH, 1);
					if (Mth.sin(t * 13.0f) > 0.0f || open > 0.6f) {
						rect(out, -1, -1 + m, 2, 1, TONGUE, 1);
					}
				}
				if (brows) {
					rect(out, -3, -5.8f + b, 2, 0.6f, BROW, 0);
					rect(out, 1, -5.8f + b, 2, 0.6f, BROW, 0);
				}
			}
			case PANIC, SCARED -> {
				if (mouth) {
					// A screaming "O".
					rect(out, -1, -2.5f + m, 2, 2.5f, DARK, 0);
					rect(out, -0.5f, -1 + m, 1, 1, TONGUE, 1);
				}
				if (brows) {
					rect(out, -3, -6 + b, 2, 0.6f, BROW, 0);
					rect(out, 1, -6 + b, 2, 0.6f, BROW, 0);
				}
				if (config.particles) {
					float drip = (t * 1.5f) % 1.0f;
					rect(out, 3.2f, -6.5f + b + drip * 2.0f, 0.6f, 1.0f, SWEAT, 1);
				}
			}
			case SAD -> {
				if (mouth) {
					// A frown: the corners pulled down, opening a little with each word.
					rect(out, -2, -2.4f + m, 4, 0.6f + 0.6f * open, DARK, 0);
					rect(out, -3, -1.8f + m, 1, 0.7f, DARK, 0);
					rect(out, 2, -1.8f + m, 1, 0.7f, DARK, 0);
				}
				if (brows) {
					sadBrows(out, b);
				}
				if (config.particles) {
					// A tear rolling down from the eye.
					float drip = (t * 0.8f) % 1.0f;
					rect(out, -2.7f, -3.5f + b + drip * 3.0f, 0.5f, 0.8f, SWEAT, 1);
				}
			}
			case HURT -> {
				if (mouth) {
					// Teeth clenched.
					rect(out, -2, -2.3f + m, 4, 1.3f, DARK, 0);
					rect(out, -1.8f, -2.1f + m, 3.6f, 0.9f, TEETH, 1);
					rect(out, -1.8f, -1.7f + m, 3.6f, 0.15f, DARK, 2);
				}
				if (brows) {
					angryBrows(out, b);
				}
			}
			case EAT -> {
				if (mouth) {
					// Chewing: open, closed, open, closed.
					if (Mth.sin(t * 9.0f) > 0.0f) {
						rect(out, -1.5f, -2.2f + m, 3, 1.2f, DARK, 0);
					} else {
						rect(out, -2, -1.9f + m, 4, 0.4f, DARK, 0);
					}
				}
			}
			case DRINK -> {
				if (mouth) {
					rect(out, -0.5f, -2.2f + m, 1, 1, DARK, 0);
				}
			}
		}
		return out;
	}

	/** The talking mouth, shaped by the sound when lip sync is on. */
	private static void talk(List<Pixel> out, Face face, FacesConfig config, float open, float m) {
		VoiceTrack.Shape shape = config.lipSync ? face.shape() : VoiceTrack.Shape.A;
		switch (shape) {
			case CLOSED -> rect(out, -1.5f, -1.8f + m, 3, 0.4f, DARK, 0);
			case O -> {
				// Round: narrow and tall.
				float h = 1.0f + open;
				rect(out, -1, -2.3f + m, 2, h, DARK, 0);
				rect(out, -1.5f, -2.0f + m, 0.5f, h - 0.6f, DARK, 0);
				rect(out, 1, -2.0f + m, 0.5f, h - 0.6f, DARK, 0);
			}
			case E -> {
				// Wide and flat, teeth showing.
				rect(out, -2.5f, -2 + m, 5, 0.6f + 0.5f * open, DARK, 0);
				rect(out, -2.3f, -2 + m, 4.6f, 0.35f, TEETH, 1);
			}
			case S -> {
				// Teeth together.
				rect(out, -2, -2.1f + m, 4, 0.9f, DARK, 0);
				rect(out, -1.8f, -1.95f + m, 3.6f, 0.6f, TEETH, 1);
			}
			case A -> {
				if (open > 0.7f) {
					rect(out, -2, -2 + m, 4, 2, DARK, 0);
					rect(out, -1, -1 + m, 2, 1, TONGUE, 1);
				} else if (open > 0.35f) {
					rect(out, -2, -2 + m, 4, 1, DARK, 0);
					rect(out, -1, -1 + m, 2, 1, DARK, 0);
				} else {
					rect(out, -1, -2 + m, 2, 1, DARK, 0);
				}
			}
		}
	}

	/** Angry: the inner ends pulled down. */
	private static void angryBrows(List<Pixel> out, float b) {
		rect(out, -3, -5.5f + b, 1, 0.6f, BROW, 0);
		rect(out, -2, -5.1f + b, 1, 0.6f, BROW, 0);
		rect(out, 2, -5.5f + b, 1, 0.6f, BROW, 0);
		rect(out, 1, -5.1f + b, 1, 0.6f, BROW, 0);
	}

	/** Sad or worried: the inner ends raised. */
	private static void sadBrows(List<Pixel> out, float b) {
		rect(out, -3, -5.2f + b, 1, 0.6f, BROW, 0);
		rect(out, -2, -5.7f + b, 1, 0.6f, BROW, 0);
		rect(out, 2, -5.2f + b, 1, 0.6f, BROW, 0);
		rect(out, 1, -5.7f + b, 1, 0.6f, BROW, 0);
	}

	/**
	 * Redraws the skin's own eyes, in its own colors, so they can blink, look
	 * around and change with the mood.
	 */
	private static void eyes(List<Pixel> out, Face face, SkinEyes skin) {
		List<Pixel> drawn = new ArrayList<>();
		eye(drawn, face, skin, skin.left());
		eye(drawn, face, skin, skin.right());
		for (Pixel p : drawn) {
			if (skin.overlay()) {
				// Eyes painted on the hat layer: same spot, but on its slightly bigger box in front of the face.
				out.add(new Pixel(p.x() * HAT, (p.y() + 4.0f) * HAT - 4.0f, p.w() * HAT, p.h() * HAT, p.color(), p.z() - 0.5f));
			} else {
				out.add(p);
			}
		}
	}

	private static void eye(List<Pixel> out, Face face, SkinEyes skin, SkinEyes.Eye e) {
		float x0 = e.x0() - 4.0f;
		float y = skin.row() - 8.0f;
		float w = e.width();
		// Which side of the face this eye is on, as you look at it; the inner corner faces the nose.
		boolean leftSide = x0 + w / 2.0f < 0.0f;
		int tone = skin.tone();
		int lash = SkinEyes.darker(e.pupil());
		Face.EyeStyle style = face.eyeStyle();

		if (style == Face.EyeStyle.HAPPY) {
			// "^"
			rect(out, x0, y, w, 1, tone, 0);
			rect(out, x0, y + 0.5f, 0.5f, 0.4f, lash, 1);
			rect(out, x0 + 0.4f, y + 0.2f, w - 0.8f, 0.4f, lash, 1);
			rect(out, x0 + w - 0.5f, y + 0.5f, 0.5f, 0.4f, lash, 1);
			return;
		}
		if (style == Face.EyeStyle.SHUT) {
			// ">" and "<", pointing at the nose.
			float tip = leftSide ? x0 + w * 0.4f : x0;
			float back = leftSide ? x0 : x0 + w * 0.5f;
			rect(out, x0, y, w, 1, tone, 0);
			rect(out, back, y + 0.05f, w * 0.5f, 0.3f, lash, 1);
			rect(out, tip, y + 0.35f, w * 0.6f, 0.3f, lash, 1);
			rect(out, back, y + 0.65f, w * 0.5f, 0.3f, lash, 1);
			return;
		}

		float wide = style == Face.EyeStyle.WIDE ? 0.35f : 0.0f;
		float top = y - wide;
		float h = 1.0f + 2.0f * wide;
		rect(out, x0, top, w, h, e.white(), 0);
		float size = style == Face.EyeStyle.WIDE ? 0.7f : 1.0f;
		float px = Mth.clamp(x0 + e.pupilAt() + face.lookX() * 0.6f, x0, x0 + w - size);
		float py = Mth.clamp(y + (1.0f - size) / 2.0f + face.lookY() * 0.25f, top, top + h - size);
		rect(out, px, py, size, size, e.pupil(), 1);

		// Eyelids: closed this much from the top, more on one side for some moods.
		float closed = 1.0f - face.eyeOpen();
		float inner = closed;
		float outer = closed;
		if (style == Face.EyeStyle.SQUINT) {
			inner = Math.max(closed, 0.45f);
			outer = Math.max(closed, 0.2f);
		} else if (style == Face.EyeStyle.DROOPY) {
			inner = Math.max(closed, 0.15f);
			outer = Math.max(closed, 0.45f);
		}
		float half = w / 2.0f;
		float innerX = leftSide ? x0 + half : x0;
		float outerX = leftSide ? x0 : x0 + half;
		if (inner > 0.01f) {
			rect(out, innerX, top, half, h * inner, tone, 2);
		}
		if (outer > 0.01f) {
			rect(out, outerX, top, half, h * outer, tone, 2);
		}
		if (closed > 0.8f) {
			rect(out, x0, top + h * 0.6f, w, 0.3f, lash, 3);
		}
	}

	/** Emotion icons floating above the head, in billboard pixels (y down, 0 = just above the head). */
	private static List<Pixel> icons(Face face, FacesConfig config) {
		List<Pixel> out = new ArrayList<>();
		float t = face.time();
		Face.Emotion emotion = face.emotion();
		Face.Reaction reaction = face.reaction();
		if (reaction == Face.Reaction.SCARED && face.weight(reaction) > 0.5f) {
			float shake = 0.3f * Mth.sin(t * 40.0f);
			bitmap(out, ALARM_MARK, shake, -2.0f, 0.6f, ALARM);
		} else if (reaction == Face.Reaction.AWKWARD && face.weight(reaction) > 0.5f && emotion != Face.Emotion.TALK) {
			// "..." slowly appearing: nobody knows what to say.
			int dots = 1 + (int) (t * 1.2f) % 3;
			bitmap(out, new String[] {DOTS[0].substring(0, dots * 2 - 1)}, 0.0f, -1.0f, 0.5f, QUIET);
		} else if (emotion == Face.Emotion.SHOUT && face.weight(emotion) > 0.5f) {
			// The anger mark throbs on the side of the head.
			float pulse = 1.0f + 0.15f * Mth.sin(t * 12.0f);
			bitmap(out, ANGER_MARK, 5.0f, -2.0f, 0.55f * pulse, ANGER);
		} else if (emotion == Face.Emotion.LAUGH && face.weight(emotion) > 0.5f) {
			// "HA"s popping up and floating away, two at a time.
			for (int k = 0; k < 2; k++) {
				float p = (t * 1.4f + k * 0.5f) % 1.0f;
				float side = ((int) (t * 1.4f + k * 0.5f)) % 2 == 0 ? -1.0f : 1.0f;
				float size = 0.5f * (1.0f - p * 0.5f);
				bitmap(out, HA, side * (3.0f + p * 2.0f), -1.0f - p * 6.0f, size, LAUGH);
			}
		} else if (emotion == Face.Emotion.PANIC && face.weight(emotion) > 0.5f) {
			float shake = 0.3f * Mth.sin(t * 40.0f);
			bitmap(out, ALARM_MARK, shake, -2.0f, 0.6f, ALARM);
		} else if (emotion == Face.Emotion.WHISPER && face.weight(emotion) > 0.5f) {
			// "..." appearing one dot at a time.
			int dots = 1 + (int) (t * 2.5f) % 3;
			bitmap(out, new String[] {DOTS[0].substring(0, dots * 2 - 1)}, 0.0f, -1.0f, 0.5f, QUIET);
		} else if (emotion == Face.Emotion.SAD && face.weight(emotion) > 0.5f) {
			// A little rain cloud, drizzling.
			bitmap(out, RAIN_CLOUD, 0.0f, -3.0f, 0.6f, CLOUD);
			for (int k = 0; k < 3; k++) {
				float p = (t * 1.6f + k / 3.0f) % 1.0f;
				out.add(new Pixel(-1.4f + k * 1.2f, -3.0f + p * 2.5f, 0.3f, 0.6f, SWEAT, 0.0f));
			}
		} else if (face.sleepy() && config.sleepy) {
			// Z's rising and growing, one after another.
			for (int k = 0; k < 3; k++) {
				float p = (face.sleepyFor() * 0.4f + k / 3.0f) % 1.0f;
				float size = 0.25f + 0.35f * p;
				bitmap(out, Z, 2.0f + p * 4.0f + Mth.sin(p * 6.0f) * 0.8f, -p * 9.0f, size, SLEEP);
			}
		}
		return out;
	}

	/** Adds a pixel icon centred on (cx, cy), each pixel {@code size} big. */
	private static void bitmap(List<Pixel> out, String[] rows, float cx, float cy, float size, int color) {
		int h = rows.length;
		int w = rows[0].length();
		float x0 = cx - w * size / 2.0f;
		float y0 = cy - h * size;
		for (int r = 0; r < h; r++) {
			for (int c = 0; c < w; c++) {
				if (rows[r].charAt(c) == 'X') {
					out.add(new Pixel(x0 + c * size, y0 + r * size, size, size, color, 0.0f));
				}
			}
		}
	}

	/** {@code layer} lifts details (teeth, tongue) slightly in front of the dark mouth. */
	private static void rect(List<Pixel> out, float x, float y, float w, float h, int color, int layer) {
		out.add(new Pixel(x, y, w, h, color, FACE_Z - 0.01f * layer));
	}

	private static void quad(PoseStack.Pose pose, VertexConsumer buffer, Pixel p, int light, float normalZ) {
		float x0 = p.x();
		float y0 = p.y();
		float x1 = p.x() + p.w();
		float y1 = p.y() + p.h();
		vertex(pose, buffer, x0, y0, p.z(), 0.0f, 0.0f, p.color(), light, normalZ);
		vertex(pose, buffer, x0, y1, p.z(), 0.0f, 1.0f, p.color(), light, normalZ);
		vertex(pose, buffer, x1, y1, p.z(), 1.0f, 1.0f, p.color(), light, normalZ);
		vertex(pose, buffer, x1, y0, p.z(), 1.0f, 0.0f, p.color(), light, normalZ);
	}

	private static void vertex(PoseStack.Pose pose, VertexConsumer buffer, float x, float y, float z, float u, float v,
			int color, int light, float normalZ) {
		buffer.addVertex(pose, x, y, z)
				.setColor(color)
				.setUv(u, v)
				.setOverlay(OverlayTexture.NO_OVERLAY)
				.setLight(light)
				.setNormal(pose, 0.0f, 0.0f, normalZ);
	}
}
