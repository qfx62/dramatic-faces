package dev.dramaticfaces;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** The settings screen, opened from Mod Menu. Every change is saved right away. */
public class FacesSettingsScreen extends Screen {
	private static final int COLUMN = 150;
	private static final int GAP = 8;
	private static final int ROW = 24;

	private final @Nullable Screen parent;
	/** The second page: emotions and reactions. */
	private boolean emotionsPage;

	public FacesSettingsScreen(@Nullable Screen parent) {
		super(Component.literal("Dramatic Faces"));
		this.parent = parent;
	}

	private interface Setter {
		void set(boolean value);
	}

	@Override
	protected void init() {
		FacesConfig config = FacesConfig.get();
		int left = width / 2 - COLUMN - GAP / 2;
		int right = width / 2 + GAP / 2;
		int y = 44;

		if (!emotionsPage) {
			toggle(left, y, "Dramatic Faces", config.enabled, v -> config.enabled = v);
			toggle(right, y, "Animate my face", config.animateSelf, v -> config.animateSelf = v);
			y += ROW;
			toggle(left, y, "Eyes", config.eyes, v -> config.eyes = v);
			toggle(right, y, "Look at speaker", config.lookAt, v -> config.lookAt = v);
			y += ROW;
			toggle(left, y, "Mouth", config.showMouth, v -> config.showMouth = v);
			toggle(right, y, "Lip sync", config.lipSync, v -> config.lipSync = v);
			y += ROW;
			toggle(left, y, "Eyebrows", config.showBrows, v -> config.showBrows = v);
			toggle(right, y, "Emotion particles", config.particles, v -> config.particles = v);
			y += ROW;
			addRenderableWidget(new HeadMotionSlider(left, y, config));
			addRenderableWidget(CycleButton.builder(FacesSettingsScreen::mouthLabel, config.mouthOffset)
					.withValues(-2, -1, 0, 1, 2)
					.create(right, y, COLUMN, 20, Component.literal("Mouth position"), (button, value) -> {
						config.mouthOffset = value;
						config.save();
					}));
		} else {
			addRenderableWidget(CycleButton.builder(FacesSettingsScreen::sensitivityLabel, config.shoutSensitivity)
					.withValues(FacesConfig.Sensitivity.values())
					.create(left, y, COLUMN, 20, Component.literal("Shout detection"), (button, value) -> {
						config.shoutSensitivity = value;
						config.save();
					}));
			toggle(right, y, "Shouting", config.shouting, v -> config.shouting = v);
			y += ROW;
			toggle(left, y, "Laughing", config.laughing, v -> config.laughing = v);
			toggle(right, y, "Panic", config.panic, v -> config.panic = v);
			y += ROW;
			toggle(left, y, "Whispering", config.whispering, v -> config.whispering = v);
			toggle(right, y, "Sad", config.sad, v -> config.sad = v);
			y += ROW;
			toggle(left, y, "Game reactions", config.reactions, v -> config.reactions = v);
			toggle(right, y, "Awkward silence", config.awkward, v -> config.awkward = v);
			y += ROW;
			toggle(left, y, "Sleepy Z's", config.sleepy, v -> config.sleepy = v);
		}

		addRenderableWidget(Button.builder(Component.literal(emotionsPage ? "< Face" : "Emotions >"), button -> {
			emotionsPage = !emotionsPage;
			rebuildWidgets();
		}).bounds(left, height - 28, COLUMN, 20).build());
		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
				.bounds(right, height - 28, COLUMN, 20).build());
	}

	private void toggle(int x, int y, String label, boolean value, Setter setter) {
		addRenderableWidget(CycleButton.onOffBuilder(value).create(x, y, COLUMN, 20, Component.literal(label), (button, on) -> {
			setter.set(on);
			FacesConfig.get().save();
		}));
	}

	private static Component mouthLabel(Integer offset) {
		if (offset == 0) {
			return Component.literal("Normal");
		}
		return Component.literal(Math.abs(offset) + (offset < 0 ? " up" : " down"));
	}

	private static Component sensitivityLabel(FacesConfig.Sensitivity sensitivity) {
		return Component.literal(switch (sensitivity) {
			case LOW -> "Only real yelling";
			case NORMAL -> "Normal";
			case HIGH -> "Very sensitive";
		});
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title, width / 2, 14, 0xFFFFFFFF);
		graphics.centeredText(font, Component.literal(emotionsPage ? "Emotions and reactions" : "Faces that react when players talk in voice chat"),
				width / 2, 27, 0xFFA0A0A0);
	}

	@Override
	public void onClose() {
		FacesConfig.get().save();
		minecraft.gui.setScreen(parent);
	}

	private static final class HeadMotionSlider extends AbstractSliderButton {
		private final FacesConfig config;

		HeadMotionSlider(int x, int y, FacesConfig config) {
			super(x, y, COLUMN, 20, Component.empty(), config.headMotion / 2.0);
			this.config = config;
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.literal("Head motion: " + Math.round(value * 200) + "%"));
		}

		@Override
		protected void applyValue() {
			config.headMotion = value * 2.0;
			config.save();
		}
	}
}
